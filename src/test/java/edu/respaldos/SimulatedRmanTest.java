package edu.respaldos;

import static org.junit.jupiter.api.Assertions.*;

import edu.respaldos.Models.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimulatedRmanTest {
    @TempDir Path dir;
    final SimulatedRman rman = new SimulatedRman(0);

    Strategy strategy(String destination) {
        return new Strategy("s1", "EST001", "d", "db", "r", "ALTA", true, "TABLESPACE", List.of("FREEPDB1:LAB_DATOS"), List.of(), true, true, true,
            "FULL", true, false, null, "DIARIA", null, List.of("02:00"), null, null, destination);
    }

    @Test
    void successfulBackupProducesPiecesThatExist() throws Exception {
        var db = new Database("db", "Lab", "sim-archivelog");
        var result = rman.execute(db, RmanScript.backup(strategy(null), db), dir.resolve("out.log"), Duration.ofMinutes(1));
        assertTrue(Rman.successful(result));
        assertTrue(result.output().startsWith(SimulatedRman.MARK));
        var pieces = Rman.pieces(result.output());
        assertEquals(4, pieces.size(), "datos, archived logs, control file y SPFILE");
        assertTrue(rman.checkFiles(db, pieces, dir.resolve("f.log")).missing().isEmpty());
    }

    @Test
    void failuresLookLikeRman() throws Exception {
        var noarch = new Database("db2", "Conta", "sim-noarchivelog");
        var online = rman.execute(noarch, RmanScript.backup(strategy(null), noarch), dir.resolve("a.log"), Duration.ofMinutes(1));
        assertFalse(Rman.successful(online));
        assertTrue(Rman.errors(online.output()).stream().anyMatch(l -> l.startsWith("ORA-19602")));
        var db = new Database("db", "Lab", "sim-archivelog");
        var missing = rman.execute(db, RmanScript.backup(strategy("/no/existe"), db), dir.resolve("b.log"), Duration.ofMinutes(1));
        assertTrue(Rman.errors(missing.output()).stream().anyMatch(l -> l.startsWith("ORA-19504")));
        assertEquals("NOARCHIVELOG", rman.diagnose(noarch, dir, List.of("/opt/oracle/backup")).logMode());
        assertEquals(-1L, rman.diagnose(db, dir, List.of("/no/existe")).freeKb().get("/no/existe"));
    }

    @Test
    void demoSeedCreatesHistoryAndAlerts() throws Exception {
        var catalog = new Catalog(dir.resolve("catalog"), new com.fasterxml.jackson.databind.ObjectMapper());
        Demo.seed(catalog, rman, dir);
        assertEquals(2, catalog.databases().size());
        assertEquals(5, catalog.strategies().size());
        assertFalse(catalog.executions().isEmpty());
        assertTrue(catalog.executions().stream().anyMatch(e -> e.status().equals("FALLIDO")));
        try (var service = new BackupService(catalog, dir, rman)) {
            @SuppressWarnings("unchecked") var alerts = (List<Alert>) service.state().get("alerts");
            var codes = alerts.stream().map(Alert::code).toList();
            assertTrue(codes.contains("NOARCHIVELOG"));
            assertTrue(codes.contains("SIN_APROBAR"));
            assertTrue(codes.contains("NO_EJECUTADA"));
            assertEquals("SIMULACION", service.state().get("mode"));
        }
    }
}
