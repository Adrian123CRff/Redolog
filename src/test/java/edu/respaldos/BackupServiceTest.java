package edu.respaldos;

import edu.respaldos.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class BackupServiceTest {
    @TempDir Path dir;
    Catalog catalog;
    Connection keepOpen;
    BackupService service;
    final Stub rman = new Stub();
    final List<Execution> notified = new CopyOnWriteArrayList<>();
    final Database db = new Database("db", "Prueba aislada", "not-oracle");
    Strategy strategy;

    static class Stub extends SimulatedRman {
        boolean invalid, unreachable, backupTimeout, verifyTimeout, verifyFailure, changedSets;
        String fault = "";
        boolean fullDisk;
        final List<String> scripts = new CopyOnWriteArrayList<>();
        Stub() { super(0); }
        @Override public DatabaseStatus diagnose(Database db, Path path, Collection<String> destinations) {
            var s = super.diagnose(db, path, destinations);
            return new DatabaseStatus(db.id(), s.checkedAt(), !unreachable, true, s.version(), s.dbName(), s.openMode(), s.logMode(),
                s.pdbs(), s.datafiles(), s.archivedLogs(), invalid ? Map.of(Models.DEFAULT_DESTINATION, -1L)
                    : fullDisk ? Map.of(Models.DEFAULT_DESTINATION, 0L) : s.freeKb(), "prueba");
        }
        @Override public Result execute(Database db, String script, Path log, Duration timeout) throws Exception {
            scripts.add(script);
            boolean verify = script.contains("VALIDATE BACKUPSET");
            if (verify ? verifyTimeout : backupTimeout) return new Result(-1, "", true);
            if (verify && verifyFailure) return new Result(1, "ORA-19599: block is corrupt", false);
            if (!verify && fault.equals("zeroExitWithError")) return new Result(0, "ORA-19504: failed to create file", false);
            if (!verify && fault.equals("noPieces")) return new Result(0, "Recovery Manager complete.", false);
            var result = super.execute(db, script, log, timeout);
            return !verify && fault.equals("truncated")
                ? new Result(0, "[Fragmento final del registro]\n" + result.output(), false) : result;
        }
        @Override public List<Long> backupSets(Database db, List<String> handles, Path log) {
            if (fault.equals("unknownSets")) throw new IllegalArgumentException("Catalogo inaccesible");
            return changedSets ? List.of(999999L) : super.backupSets(db, handles, log);
        }
        @Override public FileCheck checkFiles(Database db, List<String> files, Path log) {
            if (fault.equals("missingPiece")) return new FileCheck(Map.of(), files, true);
            if (fault.equals("emptyPiece")) return new FileCheck(Map.of(files.getFirst(), 0L), List.of(), true);
            return super.checkFiles(db, files, log);
        }
    }

    @BeforeEach void setup() throws Exception {
        catalog = new Catalog(dir.resolve("catalog"), new ObjectMapper());
        keepOpen = DriverManager.getConnection("jdbc:h2:file:" + dir.resolve("catalog/catalogo").toAbsolutePath().toString().replace('\\', '/'), "sa", "");
        catalog.save(db);
        strategy = new Strategy("s", "Prueba", "Datos de prueba", db.id(), "Tester", "ALTA", true,
            "DATABASE", null, null, true, true, true, "FULL", false, false, "2100-01-01", "DIARIA", null, List.of("02:00"), null, 60, null);
        service = new BackupService(catalog, dir, rman, notified::add);
        service.save(strategy);
    }

    @AfterEach void close() throws Exception {
        if (service != null) service.close();
        if (keepOpen != null) keepOpen.close();
    }

    Execution run(String operation) throws Exception {
        String id = service.run(strategy.id(), operation, "MANUAL", null);
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < until) {
            var e = catalog.execution(id);
            if (!e.status().equals("EJECUTANDO")) {
                if (e.status().equals("INCIERTO") || ((Map<?, ?>) service.state().get("active")).isEmpty()) return e;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("El ejecutor aislado no termino");
    }

    @Test void approvalRequiresFreshReachableDiagnostic() throws Exception {
        rman.unreachable = true;
        assertThrows(IllegalArgumentException.class, () -> service.approve(strategy.id(), "Tester"));
        assertTrue(catalog.approval(strategy.id()).isEmpty());
        assertTrue(rman.scripts.isEmpty());
    }

    @Test void preflightRejectsDestinationThatDisappearedAfterApproval() throws Exception {
        service.approve(strategy.id(), "Tester");
        rman.invalid = true;
        var e = run("BACKUP");
        assertEquals("FALLIDO", e.status());
        assertTrue(e.message().startsWith("Validacion previa"));
        assertTrue(rman.scripts.isEmpty(), "no debe despachar RMAN");
        assertFalse(Files.exists(dir.resolve("executions").resolve(e.id()).resolve("script.rma")));
    }

    @Test void previewRequiresSuccessfulSemanticValidation() throws Exception {
        var unknown = service.preview(strategy);
        assertEquals(false, unknown.get("valid"));
        assertEquals("", unknown.get("script"));
        assertEquals("", unknown.get("hash"));
        assertEquals("", unknown.get("validateScript"));
        service.diagnose(db.id());
        var valid = service.preview(strategy);
        assertEquals(true, valid.get("valid"));
        assertEquals(RmanScript.backup(strategy, db), valid.get("script"));
        var invalid = new Strategy("bad", "Objeto inexistente", null, db.id(), "Tester", "ALTA", false,
            "DATAFILE", null, List.of(65533), false, false, false, "FULL", false, false,
            "2100-01-01", "DIARIA", null, List.of(), null, null, null);
        var blocked = service.preview(invalid);
        assertEquals(false, blocked.get("valid"));
        assertEquals("", blocked.get("script"));
        assertTrue(catalog.approval(strategy.id()).isEmpty(), "previsualizar no aprueba");
    }

    @Test void zeroFreeSpaceRejectsApprovalAndDispatch() throws Exception {
        rman.fullDisk = true;
        assertThrows(IllegalArgumentException.class, () -> service.approve(strategy.id(), "Tester"));
        rman.fullDisk = false;
        service.approve(strategy.id(), "Tester");
        rman.fullDisk = true;
        var failed = run("BACKUP");
        assertEquals("FALLIDO", failed.status());
        assertTrue(rman.scripts.isEmpty());
        assertFalse(Files.exists(dir.resolve("executions").resolve(failed.id()).resolve("script.rma")));
    }

    @Test void verifiesOnlySetsFromTheSelectedBackupAndLaterFailureIsVisible() throws Exception {
        service.approve(strategy.id(), "Tester");
        var backup = run("BACKUP");
        assertEquals("EXITOSO", backup.status());
        assertFalse(backup.evidence().backupSets().isEmpty());
        assertFalse(backup.evidence().verified());
        assertFalse(backup.evidence().datafiles().isEmpty());
        var good = run("VALIDATE");
        assertEquals(backup.id(), good.evidence().verifies());
        assertEquals(backup.evidence().datafiles(), good.evidence().datafiles());
        assertTrue(good.evidence().verified());
        assertEquals(RmanScript.validateSets(backup.evidence().backupSets()), rman.scripts.get(1));
        assertTrue(ExecutionEvidence.verified(backup, catalog.executions()));
        rman.verifyFailure = true;
        var bad = run("VALIDATE");
        assertEquals("FALLIDO", bad.status());
        assertFalse(ExecutionEvidence.verified(backup, catalog.executions()));
        @SuppressWarnings("unchecked") var alerts = (List<Alert>) service.state().get("alerts");
        assertTrue(alerts.stream().anyMatch(a -> a.code().equals("VERIFICACION_FALLIDA")));
    }

    @Test void rejectsReusedOrChangedCatalogKeysBeforeValidation() throws Exception {
        service.approve(strategy.id(), "Tester");
        run("BACKUP");
        rman.changedSets = true;
        assertEquals("FALLIDO", run("VALIDATE").status());
        assertEquals(1, rman.scripts.size());
    }

    @Test void verificationTimeoutKeepsLockAndPartialEvidence() throws Exception {
        strategy = strategy.withVerifyAfter(true);
        service.save(strategy);
        service.approve(strategy.id(), "Tester");
        rman.verifyTimeout = true;
        var e = run("BACKUP");
        assertEquals("INCIERTO", e.status());
        assertFalse(e.pieces().isEmpty());
        assertFalse(e.evidence().backupSets().isEmpty());
        assertFalse(e.evidence().verified());
        assertThrows(IllegalStateException.class, () -> service.run(strategy.id(), "BACKUP", "MANUAL", null));
        service.release(db.id());
        assertTrue(((Map<?, ?>) service.state().get("active")).isEmpty());
    }

    @Test void initialTimeoutKeepsLock() throws Exception {
        service.approve(strategy.id(), "Tester");
        rman.backupTimeout = true;
        assertEquals("INCIERTO", run("BACKUP").status());
        assertFalse(((Map<?, ?>) service.state().get("active")).isEmpty());
    }

    @Test void automaticVerificationStoresStructuredEvidence() throws Exception {
        strategy = strategy.withVerifyAfter(true);
        service.save(strategy);
        service.approve(strategy.id(), "Tester");
        var e = run("BACKUP");
        assertEquals("EXITOSO", e.status());
        assertTrue(e.evidence().verified());
        assertEquals(RmanScript.validateSets(e.evidence().backupSets()), Files.readString(dir.resolve("executions").resolve(e.id()).resolve("verify.rma")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingPiece", "emptyPiece", "unknownSets", "truncated", "zeroExitWithError", "noPieces"})
    void incompleteOrInvalidBackupNeverPassesCertification(String fault) throws Exception {
        strategy = strategy.withVerifyAfter(true);
        service.save(strategy);
        service.approve(strategy.id(), "Tester");
        rman.fault = fault;
        var e = run("BACKUP");
        assertEquals("FALLIDO", e.status());
        assertFalse(e.evidence().verified());
        assertEquals(1, rman.scripts.size(), "no debe verificar evidencia incompleta");
        assertTrue(((Map<?, ?>) service.state().get("active")).isEmpty());
    }

    @Test void failedAutomaticValidationNeverReportsSuccessfulBackup() throws Exception {
        strategy = strategy.withVerifyAfter(true);
        service.save(strategy);
        service.approve(strategy.id(), "Tester");
        rman.verifyFailure = true;
        var e = run("BACKUP");
        assertEquals("FALLIDO", e.status());
        assertFalse(e.evidence().verified());
        assertFalse(e.evidence().backupSets().isEmpty(), "conservar evidencia para investigar");
    }

    @Test void completedBackupApprovalAndScheduleSurviveServiceRestart() throws Exception {
        strategy = strategy.withVerifyAfter(true);
        service.save(strategy);
        service.approve(strategy.id(), "Tester");
        var backup = run("BACKUP");
        service.close(); service = null;
        keepOpen.close(); keepOpen = null;
        catalog = new Catalog(dir.resolve("catalog"), new ObjectMapper());
        service = new BackupService(catalog, dir, rman);
        assertTrue(service.approved(catalog.strategy(strategy.id())));
        assertTrue(catalog.execution(backup.id()).evidence().verified());
        assertEquals(backup.evidence().datafiles(), catalog.execution(backup.id()).evidence().datafiles());
        assertTrue(((Map<?, ?>) service.state().get("active")).isEmpty());
        @SuppressWarnings("unchecked") var views = (List<Map<String, Object>>) service.state().get("strategies");
        assertEquals(true, views.getFirst().get("scheduled"));
        assertEquals(backup.id(), run("VALIDATE").evidence().verifies());
    }

    @Test void changedScheduleRequiresApprovalButDoesNotInvalidateBackupCoverage() throws Exception {
        service.approve(strategy.id(), "Tester");
        var changed = new Strategy(strategy.id(), strategy.name(), strategy.description(), strategy.databaseId(), strategy.responsible(), strategy.priority(), true,
            strategy.scope(), null, null, true, true, true, "FULL", false, false, "2100-01-01", "DIARIA", null, List.of("03:00"), null, 60, null);
        service.save(changed);
        assertFalse(service.approved(changed));
        assertEquals(RmanScript.coverageHash(strategy, db), RmanScript.coverageHash(changed, db));
    }

    @Test void restartKeepsOldUncertainExecutionBeyond500Records() throws Exception {
        service.close(); service = null;
        for (int i = 0; i < 502; i++) {
            String time = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i).toString();
            catalog.insert(new Execution("e" + i, "s", "Prueba", db.id(), db.name(), "BACKUP", "FULL", "MANUAL", null,
                time, time, i == 0 ? "INCIERTO" : "FALLIDO", null, "prueba", Models.DEFAULT_DESTINATION, List.of(), List.of(), "h"), null);
        }
        assertEquals(502, catalog.executions().size());
        service = new BackupService(catalog, dir, rman);
        assertEquals("e0", ((Map<?, ?>) service.state().get("active")).get(db.id()));
    }

    @Test void everyFinishedExecutionIsReportedOnceToTheNotifier() throws Exception {
        service.approve(strategy.id(), "Tester");
        rman.fault = "zeroExitWithError";
        var failed = run("BACKUP");
        assertEquals("FALLIDO", failed.status());
        assertEquals(List.of(failed.id()), notified.stream().map(Execution::id).toList());
        assertEquals("FALLIDO", notified.getFirst().status());
        rman.fault = "";
        var ok = run("BACKUP");
        assertEquals(List.of(failed.id(), ok.id()), notified.stream().map(Execution::id).toList());
    }

    @Test void validationFailureBeforeRmanIsAlsoReported() throws Exception {
        service.approve(strategy.id(), "Tester");
        rman.invalid = true;
        var e = run("BACKUP");
        assertEquals("FALLIDO", e.status());
        assertEquals(1, notified.size());
        assertTrue(notified.getFirst().message().startsWith("Validacion previa"));
    }

    @Test void keepsAnRmaFileForTheStrategyAndRemovesItWhenDeleted() throws Exception {
        var file = dir.resolve("scripts").resolve("RMA0001.rma");
        assertEquals(RmanScript.backup(strategy, db), Files.readString(file), "se escribe al guardar");
        assertEquals("RMA0001", catalog.strategy(strategy.id()).scriptCode());
        service.approve(strategy.id(), "Tester");
        assertEquals(RmanScript.backup(strategy, db), Files.readString(file));
        var changed = strategy.withArchivelogs(false);
        service.save(changed);
        assertEquals(RmanScript.backup(changed, db), Files.readString(file), "refleja la estrategia vigente");
        assertEquals("RMA0001", catalog.strategy(strategy.id()).scriptCode(), "editar conserva el codigo aunque la interfaz no lo envie");
        service.delete(strategy.id());
        assertFalse(Files.exists(file));
    }

    @Test void executionEvidenceUsesTheRmaExtension() throws Exception {
        service.approve(strategy.id(), "Tester");
        var e = run("BACKUP");
        assertEquals("EXITOSO", e.status());
        assertEquals(RmanScript.backup(strategy, db), Files.readString(dir.resolve("executions").resolve(e.id()).resolve("script.rma")));
        assertFalse(Files.exists(dir.resolve("executions").resolve(e.id()).resolve("script.rman")));
    }

    @Test void assignsConsecutiveCodesAndPublishesTheFlatCatalog() throws Exception {
        var second = new Strategy("s2", "Segunda", null, db.id(), null, "BAJA", true, "TABLESPACE", List.of("USERS", "SYSAUX"), null,
            false, true, false, "FULL", false, false, "2100-01-01", "SEMANAL", List.of("TUE", "FRI"), List.of("02:00", "14:00"), null, null, null,
            Map.of("USERS", "ALTA"), false, null);
        service.save(second);
        assertEquals("RMA0002", catalog.strategy("s2").scriptCode());
        assertTrue(Files.exists(dir.resolve("scripts/RMA0002.rma")));
        var rows = FlatCatalog.read(dir).rows();
        assertEquals(List.of("RMA0001", "RMA0002"), rows.stream().map(r -> r.get("CODIGO")).toList());
        var row = rows.get(1);
        assertEquals("scripts/RMA0002.rma", row.get("SCRIPT"));
        assertEquals("TUE,FRI", row.get("DIAS"));
        assertEquals("02:00,14:00", row.get("HORAS"));
        assertEquals("USERS (ALTA), SYSAUX (BAJA) + control file", row.get("ELEMENTOS"));
        assertEquals("NO", rows.get(0).get("APROBADA"));
        assertEquals("", rows.get(0).get("HUELLA"));
        service.approve(strategy.id(), "Tester");
        var approved = FlatCatalog.read(dir).rows().get(0);
        assertEquals("SI", approved.get("APROBADA"));
        assertEquals(RmanScript.hash(RmanScript.backup(strategy, db)), approved.get("HUELLA"));
        var e = run("BACKUP");
        var after = FlatCatalog.read(dir).rows().get(0);
        assertEquals("EXITOSO", after.get("RESULTADO"));
        assertEquals("executions/" + e.id() + "/output.log", after.get("LOG"));
        assertFalse(after.get("PIEZAS").isBlank());
        assertEquals("INTERNO", FlatCatalog.read(dir).scheduler());
    }

    @Test void importsWhatTheExternalExecutorRanAndWarnsTheDbaOfFailures() throws Exception {
        service.approve(strategy.id(), "Tester");
        var ejecutor = new Ejecutor(dir, (container, code, script, log) -> {
            Files.writeString(log, "RMAN-03009: failure of backup command on d1 channel\nORA-19504: failed to create file\n");
            return new Rman.Result(1, Files.readString(log), false);
        }, BackupService.ZONE_ID);
        var result = ejecutor.now("RMA0001");
        assertEquals("FALLIDO", result.get("RESULTADO"));
        assertEquals("FALLIDO", FlatCatalog.read(dir).rows().get(0).get("RESULTADO"), "el ejecutor actualiza el catalogo");
        service.state();
        var e = catalog.execution(result.get("ID"));
        assertEquals("FALLIDO", e.status());
        assertTrue(e.message().contains("ORA-19504"));
        assertTrue(Files.readString(dir.resolve("executions").resolve(e.id()).resolve("output.log")).contains("ORA-19504"));
        assertEquals(1, notified.size(), "el fallo del ejecutor tambien se avisa");
        service.state();
        assertEquals(1, catalog.executions().size(), "no se importa dos veces");
    }

    @Test void externalSchedulerLeavesTheTimesToTheExecutor() throws Exception {
        service.close();
        service = new BackupService(catalog, dir, rman, notified::add, false);
        assertEquals("EXTERNO", FlatCatalog.read(dir).scheduler());
        assertEquals("EXTERNO", service.state().get("scheduler"));
    }
}
