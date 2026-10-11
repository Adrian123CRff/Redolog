package edu.respaldos;

import edu.respaldos.RecoveryAdvisor.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryAdvisorTest {
    static final String FILES = """
        PARAM_CTL|/opt/oracle/oradata/FREE/control01.ctl, /opt/oracle/oradata/FREE/control02.ctl
        PARAM_SPF|/opt/oracle/product/26ai/dbhomeFree/dbs/spfileFREE.ora
        DF|1|CDB$ROOT|SYSTEM|SYSTEM|/opt/oracle/oradata/FREE/system01.dbf
        DF|4|CDB$ROOT|UNDOTBS1|ONLINE|/opt/oracle/oradata/FREE/undotbs01.dbf
        DF|9|FREEPDB1|SYSTEM|SYSTEM|/opt/oracle/oradata/FREE/FREEPDB1/system01.dbf
        DF|13|FREEPDB1|LAB_DATOS|ONLINE|/opt/oracle/oradata/FREE/FREEPDB1/lab_datos01.dbf
        LOG|1|51|INACTIVE|YES
        LOG|2|52|CURRENT|NO
        LOG|3|50|INACTIVE|YES
        LOGF|1|OK|/opt/oracle/oradata/FREE/redo01.log
        LOGF|1|OK|/opt/oracle/oradata/espejo/redo01b.log
        LOGF|2|OK|/opt/oracle/oradata/FREE/redo02.log
        LOGF|2|OK|/opt/oracle/oradata/espejo/redo02b.log
        LOGF|3|OK|/opt/oracle/oradata/FREE/redo03.log
        LOGF|3|OK|/opt/oracle/oradata/espejo/redo03b.log
        """;
    static final String OPEN = """
        INST|OPEN|ALLOWED|STARTED
        NOW|2026-10-12 08:30:00
        DB|READ WRITE|ARCHIVELOG|1516667409|7340772
        PDB|FREEPDB1|READ WRITE|NO
        CNT|FREEPDB1|LAB_DEMO.PEDIDOS|120
        """ + FILES;
    static final String SNAPSHOT = "TOMADA|2026-10-12 07:30:00\nSCN|7300000\nCNT|FREEPDB1|LAB_DEMO.PEDIDOS|120\n";

    static Facts facts(String text) { return new Facts("rman-lab").state("running healthy").parse(text); }
    static String all(List<Finding> list) {
        var s = new StringBuilder();
        for (var f : list) { s.append(f.severity()).append(' ').append(f.title()).append('\n'); f.steps().forEach(st -> s.append(st.commands()).append('\n')); }
        return s.toString();
    }

    @Test void healthyDatabaseHasNoFindings() {
        var list = RecoveryAdvisor.analyze(facts(OPEN).snapshot(SNAPSHOT));
        assertEquals(1, list.size());
        assertEquals("OK", list.get(0).severity());
    }

    @Test void stoppedContainerSaysToStartIt() {
        var list = RecoveryAdvisor.analyze(new Facts("rman-lab").state("exited"));
        assertEquals("CRITICO", list.get(0).severity());
        assertTrue(all(list).contains("docker start rman-lab"));
    }

    @Test void deletedUserDatafileIsRestoredByTablespaceWithoutStoppingTheBase() {
        var text = all(RecoveryAdvisor.analyze(facts(OPEN).missing("/opt/oracle/oradata/FREE/FREEPDB1/lab_datos01.dbf")));
        assertTrue(text.contains("ALTER TABLESPACE LAB_DATOS OFFLINE IMMEDIATE"));
        assertTrue(text.contains("RESTORE TABLESPACE FREEPDB1:LAB_DATOS;\nRECOVER TABLESPACE FREEPDB1:LAB_DATOS;"));
        assertFalse(text.contains("SHUTDOWN"), "el resto de la base sigue abierta");
    }

    @Test void systemDatafileNeedsMountAndRestoreDatafile() {
        var text = all(RecoveryAdvisor.analyze(facts(OPEN.replace("INST|OPEN", "INST|MOUNTED").replace("DB|READ WRITE", "DB|MOUNTED") + "REC|1|FILE NOT FOUND\n")));
        assertTrue(text.startsWith("CRITICO"));
        assertTrue(text.contains("RESTORE DATAFILE 1;\nRECOVER DATAFILE 1;\nALTER DATABASE OPEN;"));
    }

    @Test void allControlFilesLostWhileDownUsesKnownFilesAndAutobackupWithDbid() {
        var f = new Facts("rman-lab").state("running healthy").known("DB|READ WRITE|ARCHIVELOG|1516667409|1\n" + FILES)
            .missing("/opt/oracle/oradata/FREE/control01.ctl", "/opt/oracle/oradata/FREE/control02.ctl");
        var list = RecoveryAdvisor.analyze(f);
        var text = all(list);
        assertTrue(text.contains("SET DBID 1516667409;"));
        assertTrue(text.contains("RESTORE CONTROLFILE FROM AUTOBACKUP;"));
        assertTrue(text.contains("ALTER DATABASE OPEN RESETLOGS;"));
        assertTrue(list.stream().anyMatch(x -> x.title().startsWith("Despues del OPEN RESETLOGS")), "pide respaldar despues");
    }

    @Test void oneControlFileLostIsCopiedFromTheOther() {
        var text = all(RecoveryAdvisor.analyze(facts(OPEN).missing("/opt/oracle/oradata/FREE/control02.ctl")));
        assertTrue(text.contains("cp /opt/oracle/oradata/FREE/control01.ctl /opt/oracle/oradata/FREE/control02.ctl"));
        assertFalse(text.contains("RESETLOGS"));
    }

    @Test void lostRedoMemberWithCopyIsRecreatedWithoutLoss() {
        var list = RecoveryAdvisor.analyze(facts(OPEN).missing("/opt/oracle/oradata/FREE/redo02.log"));
        assertEquals("BAJO", list.get(0).severity());
        assertTrue(all(list).contains("ALTER SYSTEM SWITCH LOGFILE;\nALTER SYSTEM CHECKPOINT;\nALTER DATABASE DROP LOGFILE MEMBER '/opt/oracle/oradata/FREE/redo02.log';"));
    }

    @Test void lostRedoMemberOfInactiveGroupDoesNotSwitchLogs() {
        // Cambiar de log podria convertir justo a ese grupo en el actual (ORA-01609 al quitar el miembro).
        var text = all(RecoveryAdvisor.analyze(facts(OPEN).missing("/opt/oracle/oradata/espejo/redo01b.log")));
        assertFalse(text.contains("SWITCH LOGFILE"));
        assertTrue(text.contains("ALTER DATABASE DROP LOGFILE MEMBER '/opt/oracle/oradata/espejo/redo01b.log';"));
    }

    @Test void lostCurrentRedoGroupIsIncompleteRecoveryUntilItsSequence() {
        var text = all(RecoveryAdvisor.analyze(facts(OPEN).missing("/opt/oracle/oradata/FREE/redo02.log", "/opt/oracle/oradata/espejo/redo02b.log")));
        assertTrue(text.contains("SET UNTIL SEQUENCE 52 THREAD 1;"));
        assertTrue(text.contains("OPEN RESETLOGS"));
    }

    @Test void lostInactiveArchivedRedoGroupIsCleared() {
        var text = all(RecoveryAdvisor.analyze(facts(OPEN).missing("/opt/oracle/oradata/FREE/redo01.log", "/opt/oracle/oradata/espejo/redo01b.log")));
        assertTrue(text.contains("ALTER DATABASE CLEAR LOGFILE GROUP 1;"));
        assertFalse(text.contains("RESETLOGS"));
    }

    @Test void closedPdbIsOpened() {
        var text = all(RecoveryAdvisor.analyze(facts(OPEN.replace("PDB|FREEPDB1|READ WRITE", "PDB|FREEPDB1|MOUNTED").replace("CNT|FREEPDB1|LAB_DEMO.PEDIDOS|120\n", ""))));
        assertTrue(text.contains("ALTER PLUGGABLE DATABASE FREEPDB1 OPEN;"));
    }

    @Test void downInstanceWithoutMissingFilesSaysStartup() {
        var list = RecoveryAdvisor.analyze(new Facts("rman-lab").state("running healthy").known(FILES).parse("ALERT|ORA-00600: internal error"));
        assertEquals("La instancia esta apagada", list.get(0).title());
        assertTrue(all(list).contains("STARTUP;"));
    }

    @Test void droppedTableAfterSnapshotIsFlashedBackFromRecycleBin() {
        var f = facts(OPEN.replace("CNT|FREEPDB1|LAB_DEMO.PEDIDOS|120\n", "CNT|FREEPDB1|LAB_DEMO.CLIENTES|5\n")
            + "BIN|FREEPDB1|LAB_DEMO|PEDIDOS|BIN$abc==$0|2026-10-12:08:10:00\nBIN|FREEPDB1|LAB_DEMO|VIEJA|BIN$old==$0|2026-10-01:08:10:00\n").snapshot(SNAPSHOT);
        var text = all(RecoveryAdvisor.analyze(f));
        assertTrue(text.contains("FLASHBACK TABLE LAB_DEMO.\"PEDIDOS\" TO BEFORE DROP;"));
        assertFalse(text.contains("VIEJA"), "las eliminadas antes de la foto no se proponen");
        assertFalse(text.contains("PURGE"), "no la confunde con un DROP PURGE");
    }

    @Test void deletedRowsComparedWithSnapshotUseFlashbackQuery() {
        var text = all(RecoveryAdvisor.analyze(facts(OPEN.replace("PEDIDOS|120", "PEDIDOS|80")).snapshot(SNAPSHOT)));
        assertTrue(text.contains("Faltan filas en LAB_DEMO.PEDIDOS (antes 120, ahora 80)"));
        assertTrue(text.contains("AS OF SCN 7300000"), "usa el SCN exacto de la foto");
        assertTrue(text.contains("UNTIL SCN 7300000"));
        assertTrue(text.contains("RECOVER TABLE LAB_DEMO.PEDIDOS OF PLUGGABLE DATABASE FREEPDB1"));
    }

    @Test void purgedTableIsRecoveredFromBackups() {
        var text = all(RecoveryAdvisor.analyze(facts(OPEN.replace("CNT|FREEPDB1|LAB_DEMO.PEDIDOS|120\n", "CNT|FREEPDB1|LAB_DEMO.CLIENTES|5\n")).snapshot(SNAPSHOT)));
        assertTrue(text.contains("ya no existe y no esta en la papelera"));
        assertTrue(text.contains("REMAP TABLE LAB_DEMO.PEDIDOS:PEDIDOS_RECUPERADA;"));
    }

    @Test void lockedUserAndOfflineTablespaceAndNoArchivelog() {
        var text = all(RecoveryAdvisor.analyze(facts(OPEN.replace("ARCHIVELOG", "NOARCHIVELOG")
            + "USR|FREEPDB1|LAB_DEMO|LOCKED\nTS|FREEPDB1|USERS|OFFLINE\n")));
        assertTrue(text.contains("ALTER USER LAB_DEMO ACCOUNT UNLOCK;"));
        assertTrue(text.contains("ALTER TABLESPACE USERS ONLINE;"));
        assertTrue(text.contains("ALTER DATABASE ARCHIVELOG;"));
    }
}
