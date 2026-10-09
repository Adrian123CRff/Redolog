package edu.respaldos;

import edu.respaldos.Models.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.quartz.spi.OperableTrigger;
import static org.junit.jupiter.api.Assertions.*;

class SchedulesTest {
    static final ZoneId Z = BackupService.ZONE_ID;
    static Instant at(String local) { return LocalDateTime.parse(local).atZone(Z).toInstant(); }
    static Strategy strategy(String frequency, List<String> times, Integer interval, List<String> days) {
        return new Strategy("s", "Horario", "d", "db", "r", "ALTA", true, "DATABASE", null, null,
            false, false, false, "FULL", false, false, "2026-09-28", frequency, days, times, interval, null, null);
    }

    @Test void mergesAllDailyHoursBeforeLimiting() {
        var s = strategy("DIARIA", List.of("08:00", "12:00", "18:00"), null, null);
        assertEquals(at("2026-09-28T12:00"), Schedules.next(s, at("2026-09-28T10:00"), Z));
        assertEquals(List.of(at("2026-09-28T12:00"), at("2026-09-28T18:00"), at("2026-09-29T08:00")),
            Schedules.occurrences(s, at("2026-09-28T10:00"), at("2026-10-10T00:00"), Z, 3));
    }

    @Test void continuousIntervalCrossesMidnight() {
        var s = strategy("INTERVALO", List.of("20:00"), 5, null);
        assertEquals(List.of(at("2026-09-28T20:00"), at("2026-09-29T01:00"), at("2026-09-29T06:00"), at("2026-09-29T11:00")),
            Schedules.occurrences(s, at("2026-09-28T10:00"), at("2026-10-01T00:00"), Z, 4));
    }

    @Test void quartzFiringAndPreviewAgreeWithExcludedDays() {
        var s = strategy("INTERVALO", List.of("20:00"), 5, List.of("MON", "WED"));
        var trigger = (OperableTrigger) Schedules.triggers(s, Z).get(0);
        var calendar = Schedules.calendar(s, Z);
        trigger.computeFirstFireTime(calendar);
        var actual = new ArrayList<Instant>();
        for (int i = 0; i < 8; i++) {
            actual.add(trigger.getNextFireTime().toInstant());
            trigger.triggered(calendar);
        }
        assertEquals(Schedules.occurrences(s, at("2026-09-28T00:00"), at("2026-10-20T00:00"), Z, 8), actual);
        assertEquals(at("2026-09-30T02:00"), actual.get(1), "el martes se omite sin reiniciar el intervalo");
    }

    @Test void respectsStartAndEmptyRange() {
        var s = strategy("DIARIA", List.of("08:00"), null, null);
        assertEquals(at("2026-09-28T08:00"), Schedules.next(s, at("2025-01-01T00:00"), Z));
        assertTrue(Schedules.occurrences(s, at("2026-09-29T00:00"), at("2026-09-28T00:00"), Z, 5).isEmpty());
    }

    @Test void oneTimeStrategyIsNotCyclic() {
        var s = strategy("UNA_VEZ", List.of("08:00", "20:00"), null, List.of("TUE"));
        assertEquals(List.of(at("2026-09-28T08:00"), at("2026-09-28T20:00")),
            Schedules.occurrences(s, at("2026-09-01T00:00"), at("2027-12-31T00:00"), Z, 50), "solo la fecha de inicio, sin repetirse");
        assertEquals(Models.DAYS, s.days(), "los dias no aplican a una ejecucion unica");
        assertTrue(Schedules.describe(s).startsWith("Una sola vez"));
        assertTrue(RmanScript.check(s, null).stream().anyMatch(i -> i.code().equals("NO_CICLICA")));
        assertTrue(RmanScript.check(s, null).stream().noneMatch(i -> i.code().equals("FRECUENCIA_INSUFICIENTE")));
    }
}
