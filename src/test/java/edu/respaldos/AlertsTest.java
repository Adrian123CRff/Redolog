package edu.respaldos;

import static org.junit.jupiter.api.Assertions.*;

import edu.respaldos.Models.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AlertsTest {
    static final ZoneId ZONE = ZoneId.of("America/Costa_Rica");
    static final Instant NOW = Instant.parse("2026-09-23T18:00:00Z"); // 12:00 en Costa Rica

    static Strategy weekly(String priority) {
        return new Strategy("s1", "EST002 - Base completa", "Respaldo semanal", "db1", "Adrian", priority, true, "DATABASE", null, null,
            false, true, true, "FULL", false, false, "2026-09-01", "SEMANAL", List.of("SUN"), List.of("02:00"), null, null, null);
    }

    static Alerts.Input input(Strategy s, List<Execution> executions, Set<String> occurrences, Approval approval) {
        var db = new Database("db1", "Laboratorio", "rman-lab");
        var st = new DatabaseStatus("db1", NOW.toString(), true, true, "23", "FREE", "READ WRITE", "ARCHIVELOG", List.of(), List.of(), 3,
            Map.of("/opt/oracle/backup", 50_000_000L), "ok");
        return new Alerts.Input(List.of(db), Map.of("db1", st), List.of(s), approval == null ? Map.of() : Map.of(s.id(), approval),
            x -> RmanScript.backup(x, db), executions, Map.of(s.id(), occurrences), Map.of(), NOW, ZONE);
    }

    static Approval approval(Strategy s, Instant at) {
        return new Approval(s.id(), RmanScript.hash(RmanScript.backup(s, new Database("db1", "Laboratorio", "rman-lab"))), at.toString(), "Adrian");
    }

    static Set<String> codes(List<Alert> alerts) { return new HashSet<>(alerts.stream().map(Alert::code).toList()); }

    @Test
    void unapprovedStrategyIsFlagged() {
        var alerts = Alerts.evaluate(input(weekly("BAJA"), List.of(), Set.of(), null));
        assertTrue(codes(alerts).contains("SIN_APROBAR"));
        assertTrue(codes(alerts).contains("SIN_RESPALDO_ARCHIVELOGS"));
        assertEquals("ALERTA", Alerts.LEVELS.get(0));
    }

    @Test
    void missedRunAndFrequencyVsPriority() {
        var s = weekly("ALTA");
        // Aprobada hace 10 dias; el domingo 20/09 a las 02:00 no hay registro de ejecucion.
        var alerts = Alerts.evaluate(input(s, List.of(), Set.of(), approval(s, NOW.minus(Duration.ofDays(10)))));
        var set = codes(alerts);
        assertTrue(set.contains("NO_EJECUTADA"));
        assertTrue(set.contains("FRECUENCIA_INSUFICIENTE"), "semanal no cumple 24 h de una prioridad alta");
        assertTrue(set.contains("SIN_RESPALDO_RECIENTE"));
        assertTrue(set.contains("INCLUIR_ARCHIVELOGS"), "recomendacion con accion para prioridad alta");
        var rec = alerts.stream().filter(a -> a.code().equals("INCLUIR_ARCHIVELOGS")).findFirst().orElseThrow();
        assertEquals("RECOMENDACION", rec.level());
        assertEquals("AGREGAR_ARCHIVELOGS", rec.action());
    }

    @Test
    void executedOccurrenceIsNotMissedAndSuccessNeedsVerification() {
        var s = weekly("BAJA");
        Instant sunday = LocalDateTime.of(2026, 9, 20, 2, 0).atZone(ZONE).toInstant();
        var ok = new Execution("e1", s.id(), s.name(), "db1", "Laboratorio", "BACKUP", "FULL", "HORARIO", sunday.toString(),
            sunday.toString(), sunday.plusSeconds(90).toString(), "EXITOSO", 0, "ok", "/opt/oracle/backup", List.of("/x.bkp"), List.of(), "h");
        var alerts = Alerts.evaluate(input(s, List.of(ok), Set.of(s.id() + "|" + sunday), approval(s, NOW.minus(Duration.ofDays(10)))));
        var set = codes(alerts);
        assertFalse(set.contains("NO_EJECUTADA"));
        assertFalse(set.contains("SIN_RESPALDO_RECIENTE"));
        assertTrue(set.contains("SIN_VERIFICAR"));
    }

    @Test
    void failedExecutionRaisesAlert() {
        var s = weekly("MEDIA");
        var failed = new Execution("e2", s.id(), s.name(), "db1", "Laboratorio", "BACKUP", "FULL", "MANUAL", null,
            NOW.minusSeconds(600).toString(), NOW.minusSeconds(500).toString(), "FALLIDO", 1, "RMAN informo un error", "/opt/oracle/backup", List.of(), List.of(), "h");
        var alert = Alerts.evaluate(input(s, List.of(failed), Set.of(), null)).stream().filter(a -> a.code().equals("EJECUCION_FALLIDA")).findFirst().orElseThrow();
        assertEquals("ALERTA", alert.level());
        assertEquals("VER:e2", alert.action());
    }

    @Test
    void scheduleOccurrencesAndDescription() {
        var s = new Strategy("s9", "EST003", null, "db1", null, "ALTA", true, "COMPONENTS", null, null, true, false, false, "FULL", false, false,
            "2026-09-23", "INTERVALO", List.of("MON", "TUE", "WED", "THU", "FRI"), List.of("00:30"), 4, null, null);
        assertEquals(List.of("0 30 0/4 ? * MON,TUE,WED,THU,FRI"), Schedules.cron(s));
        var next = Schedules.occurrences(s, NOW, NOW.plus(Duration.ofHours(12)), ZONE, 10);
        assertEquals(LocalDateTime.of(2026, 9, 23, 12, 30).atZone(ZONE).toInstant(), next.get(0));
        assertEquals("Cada 4 h desde las 00:30, lun, mar, mie, jue, vie", Schedules.describe(s));
    }
}
