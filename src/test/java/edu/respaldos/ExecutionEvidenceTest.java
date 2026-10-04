package edu.respaldos;

import edu.respaldos.Models.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionEvidenceTest {
    final Database db = RmanScriptTest.DB;
    final DatabaseStatus status = RmanScriptTest.status("ARCHIVELOG", 50_000_000);
    final Strategy level0 = strategy("zero", "TABLESPACE", "LEVEL0", List.of("FREEPDB1:LAB_DATOS"), null);
    final Strategy level1 = strategy("one", "DATAFILE", "LEVEL1", null, List.of(13));

    Strategy strategy(String id, String scope, String method, List<String> tablespaces, List<Integer> datafiles) {
        return new Strategy(id, id, "Prueba", db.id(), "Tester", "ALTA", false, scope, tablespaces, datafiles,
            false, false, false, method, false, true, "2026-09-01", "DIARIA", null, List.of(), null, null, null);
    }

    Execution backup(Strategy s, List<Integer> files, boolean verified) {
        return new Execution("b", s.id(), s.name(), db.id(), db.name(), "BACKUP", s.method(), "MANUAL", null,
            "2026-09-23T12:00:00Z", "2026-09-23T12:01:00Z", "EXITOSO", 0, "ok", s.destination(), List.of("x"), List.of(), "h",
            new Evidence(RmanScript.coverageHash(s, db), List.of("x"), List.of(1L), null, verified, files));
    }

    boolean has(Strategy base, Strategy target, List<Execution> executions) {
        return ExecutionEvidence.hasVerifiedLevel0(List.of(base, target), db, status, target, executions);
    }

    @Test void configurationAloneAndUnverifiedBackupsAreNotEvidence() {
        assertFalse(has(level0, level1, List.of()));
        assertFalse(has(level0, level1, List.of(backup(level0, List.of(13), false))));
    }

    @Test void verifiedInactiveManualLevel0CoversDatafileFromTablespace() {
        assertTrue(has(level0, level1, List.of(backup(level0, List.of(13), true))));
    }

    @Test void fullIsNotAnIncrementalBase() {
        var full = strategy("full", "DATABASE", "FULL", null, null);
        assertFalse(has(full, level1, List.of(backup(full, List.of(1, 13), true))));
    }

    @Test void snapshotCannotCoverNewFilesOrCertifyLegacyEvidence() {
        var all = strategy("all", "DATABASE", "LEVEL0", null, null);
        var target = strategy("all1", "DATABASE", "CUMULATIVE", null, null);
        assertFalse(has(all, target, List.of(backup(all, List.of(1), true))));
        assertFalse(has(all, target, List.of(backup(all, List.of(), true))));
        assertTrue(has(all, target, List.of(backup(all, List.of(1, 13), true))));
    }

    @Test void laterVerificationFailureInvalidatesBase() {
        var b = backup(level0, List.of(13), true);
        var failed = new Execution("v", level0.id(), level0.name(), db.id(), db.name(), "VALIDATE", "VALIDATE", "MANUAL", null,
            "2026-09-23T13:00:00Z", "2026-09-23T13:01:00Z", "FALLIDO", 1, "error", level0.destination(), List.of(), List.of(), "h",
            new Evidence(b.evidence().coverageHash(), List.of("x"), List.of(1L), b.id(), false, List.of(13)));
        assertFalse(has(level0, level1, List.of(b, failed)));
    }

    @Test void changedCoverageAndMissingTargetsAreNotCertified() {
        var b = backup(level0, List.of(13), true);
        assertFalse(has(level0.withArchivelogs(true), level1, List.of(b)));
        var missing = strategy("missing", "DATAFILE", "LEVEL1", null, List.of(65533));
        assertFalse(has(level0, missing, List.of(b)));
    }
}
