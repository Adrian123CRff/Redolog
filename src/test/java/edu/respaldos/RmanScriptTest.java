package edu.respaldos;

import static org.junit.jupiter.api.Assertions.*;

import edu.respaldos.Models.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RmanScriptTest {
    static final Database DB = new Database("db1", "Laboratorio", "rman-lab");

    static Strategy strategy(String scope, List<String> ts, List<Integer> df, String method, boolean arch, boolean cf, boolean sp, boolean compressed) {
        return new Strategy("s1", "EST001 - Pedidos", "Pedidos del laboratorio", "db1", "Adrian", "ALTA", true, scope, ts, df,
            arch, cf, sp, method, compressed, false, "2026-09-01", "DIARIA", null, List.of("02:00"), null, 60, "/opt/oracle/backup");
    }

    static DatabaseStatus status(String logMode, long freeKb) {
        return new DatabaseStatus("db1", "2026-09-23T12:00:00Z", true, true, "23.26.1.0.0", "FREE", "READ WRITE", logMode,
            List.of("FREEPDB1 (READ WRITE)"),
            List.of(new Datafile(1, "CDB$ROOT", "SYSTEM", 1_000_000_000L, "/opt/oracle/oradata/FREE/system01.dbf"),
                    new Datafile(13, "FREEPDB1", "LAB_DATOS", 33_554_432L, "/opt/oracle/oradata/FREE/FREEPDB1/lab01.dbf")),
            5, Map.of("/opt/oracle/backup", freeKb), "ok");
    }

    @Test
    void incrementalCumulativeTablespaceWithAllComponents() {
        String script = RmanScript.backup(strategy("TABLESPACE", List.of("freepdb1:lab_datos"), null, "CUMULATIVE", true, true, true, true), DB);
        assertTrue(script.contains("ALLOCATE CHANNEL d1 DEVICE TYPE DISK FORMAT '/opt/oracle/backup/%d_%T_%U.bkp';"));
        assertTrue(script.contains("BACKUP INCREMENTAL LEVEL 1 CUMULATIVE AS COMPRESSED BACKUPSET TAG 'EST001_PEDIDOS' TABLESPACE FREEPDB1:LAB_DATOS;"));
        assertTrue(script.contains("ARCHIVELOG ALL NOT BACKED UP 1 TIMES;"));
        assertTrue(script.indexOf("TABLESPACE FREEPDB1") < script.indexOf("ARCHIVELOG ALL"), "los archived logs van despues de los datos");
        assertTrue(script.indexOf("ARCHIVELOG ALL") < script.indexOf("CURRENT CONTROLFILE"), "el control file va al final");
        assertTrue(script.contains("SPFILE;"));
        assertTrue(script.endsWith("EXIT;\n"));
    }

    @Test
    void fullDatabaseWithoutOptions() {
        String script = RmanScript.backup(strategy("DATABASE", null, null, "FULL", false, false, false, false), DB);
        assertTrue(script.contains("  BACKUP TAG 'EST001_PEDIDOS' DATABASE;"));
        assertFalse(script.contains("ARCHIVELOG"));
        assertFalse(script.contains("CONTROLFILE"));
    }

    @Test
    void datafilesAndComponentsOnly() {
        assertTrue(RmanScript.backup(strategy("DATAFILE", null, List.of(13, 7), "LEVEL0", false, false, false, false), DB)
            .contains("BACKUP INCREMENTAL LEVEL 0 TAG 'EST001_PEDIDOS' DATAFILE 7, 13;"));
        String arch = RmanScript.backup(strategy("COMPONENTS", null, null, "LEVEL1", true, false, false, false), DB);
        assertFalse(arch.contains("INCREMENTAL"), "los componentes no admiten incrementales");
        assertTrue(arch.contains("ARCHIVELOG ALL NOT BACKED UP 1 TIMES"));
        assertThrows(IllegalArgumentException.class, () -> strategy("COMPONENTS", null, null, "FULL", false, false, false, false));
    }

    @Test
    void validationScriptUsesRmanMechanisms() {
        String script = RmanScript.validate(strategy("TABLESPACE", List.of("FREEPDB1:LAB_DATOS"), null, "FULL", true, true, true, false));
        assertTrue(script.contains("CROSSCHECK BACKUP TAG 'EST001_PEDIDOS';"));
        assertTrue(script.contains("RESTORE TABLESPACE FREEPDB1:LAB_DATOS VALIDATE;"));
        assertTrue(script.contains("RESTORE CONTROLFILE VALIDATE;"));
    }

    @Test
    void commentsAreAsciiAndCannotBreakTheScript() {
        var s = new Strategy("s2", "Nómina '}", null, "db1", null, "MEDIA", false, "DATABASE", null, null, false, false, false,
            "FULL", false, false, null, null, null, List.of(), null, null, null);
        String script = RmanScript.backup(s, DB);
        assertTrue(script.startsWith("# Estrategia: Nomina '}\n"));
        assertEquals("NOMINA", s.tag());
        assertThrows(IllegalArgumentException.class, () -> new Strategy("s3", "a\nb", null, "db1", null, "MEDIA", false, "DATABASE", null, null,
            false, false, false, "FULL", false, false, null, null, null, List.of(), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Strategy("s4", "x", null, "db1", null, "MEDIA", false, "DATABASE", null, null,
            false, false, false, "FULL", false, false, null, null, null, List.of(), null, null, "/tmp/../etc'"));
    }

    @Test
    void checkDetectsMissingTablespaceAndArchivelogMode() {
        var s = strategy("TABLESPACE", List.of("LAB_DATOS"), null, "FULL", false, true, true, false);
        var issues = RmanScript.check(s, status("ARCHIVELOG", 10_000_000));
        assertTrue(issues.stream().anyMatch(i -> i.level().equals("ERROR") && i.code().equals("TABLESPACE_INEXISTENTE") && i.message().contains("PDB:TABLESPACE")));
        assertTrue(issues.stream().anyMatch(i -> i.level().equals("RECOMENDACION") && i.code().equals("INCLUIR_ARCHIVELOGS")));
        assertTrue(issues.stream().anyMatch(i -> i.level().equals("INFORMATIVA") && i.code().equals("ARCHIVELOG")));
    }

    @Test
    void checkWarnsForNoarchivelogAndSpace() {
        var s = strategy("DATABASE", null, null, "FULL", true, true, true, false);
        var issues = RmanScript.check(s, status("NOARCHIVELOG", 100));
        assertTrue(issues.stream().anyMatch(i -> i.level().equals("ADVERTENCIA") && i.code().equals("NOARCHIVELOG")));
        assertTrue(issues.stream().anyMatch(i -> i.level().equals("ERROR") && i.code().equals("ARCHIVELOG_SIN_MODO")));
        assertTrue(issues.stream().anyMatch(i -> i.code().equals("ESPACIO_INSUFICIENTE")));
        assertTrue(RmanScript.check(s, status("ARCHIVELOG", -1)).stream().anyMatch(i -> i.code().equals("DESTINO_INEXISTENTE")));
    }

    @Test
    void hashChangesWithTheScript() {
        var a = strategy("DATABASE", null, null, "FULL", false, true, true, false);
        var b = a.withArchivelogs(true);
        assertNotEquals(RmanScript.hash(RmanScript.backup(a, DB)), RmanScript.hash(RmanScript.backup(b, DB)));
        assertEquals(RmanScript.hash(RmanScript.backup(a, DB)), RmanScript.hash(RmanScript.backup(a, DB)));
    }

    @Test
    void rmanOutputParsing() {
        String out = """
            piece handle=/opt/oracle/backup/FREE_20260922_01l3s8nk_1_1_1.bkp tag=EST001 comment=NONE
            no parent backup or copy of datafile 13 found
            RMAN-00571: ===========================================================
            RMAN-00569: =============== ERROR MESSAGE STACK FOLLOWS ===============
            RMAN-03002: failure of backup command at 09/22/2026 21:17:48
            RMAN-06019: could not translate tablespace name "LAB_DATOS"
            """;
        assertEquals(List.of("/opt/oracle/backup/FREE_20260922_01l3s8nk_1_1_1.bkp"), Rman.pieces(out));
        assertEquals(List.of("RMAN-03002: failure of backup command at 09/22/2026 21:17:48", "RMAN-06019: could not translate tablespace name \"LAB_DATOS\""), Rman.errors(out));
        assertEquals(1, Rman.warnings(out).size());
        assertFalse(Rman.successful(new Rman.Result(0, out, false)));
    }

    @Test
    void legacyStrategyAndExecutionStillLoad() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        var s = json.readValue("""
            {"id":"x","name":"EST001","databaseId":"db1","scope":"TABLESPACE","tablespaces":["FREEPDB1:LAB_DATOS"],
             "method":"FULL","archivelogs":true,"controlfile":true,"priority":"ALTA","times":["13:00"],"enabled":false}""", Strategy.class);
        assertTrue(s.spfile(), "la antigua casilla 'control file y SPFILE' se conserva");
        assertEquals("DIARIA", s.frequency());
        assertEquals(Models.DEFAULT_DESTINATION, s.destination());
        var e = json.readValue("{\"id\":\"e\",\"strategyId\":\"x\",\"databaseId\":\"db1\",\"operation\":\"BACKUP\",\"source\":\"MANUAL\",\"startedAt\":\"2026-09-22T21:17:33Z\",\"status\":\"CORRECTA\"}", Execution.class);
        assertEquals("EXITOSO", e.status());
    }
}
