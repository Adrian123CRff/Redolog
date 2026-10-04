package edu.respaldos;

import edu.respaldos.Models.*;
import java.time.*;
import java.util.*;
import org.quartz.*;
import org.quartz.impl.calendar.WeeklyCalendar;

/** Traduce el "CUÁNDO" de una estrategia a expresiones cron de Quartz y calcula sus ocurrencias. */
public final class Schedules {
    private Schedules() {}
    private static final Map<String, String> DAY_NAMES = Map.of("MON", "lun", "TUE", "mar", "WED", "mie", "THU", "jue", "FRI", "vie", "SAT", "sab", "SUN", "dom");

    public static List<String> cron(Strategy s) {
        Models.require(!s.frequency().equals("INTERVALO"), "Los intervalos usan un disparador continuo, no cron.");
        String days = String.join(",", s.days());
        return s.times().stream().map(LocalTime::parse).map(t -> "0 " + t.getMinute() + " " + t.getHour() + " ? * " + days).toList();
    }

    public static Instant startsAt(Strategy s, ZoneId zone) {
        return s.frequency().equals("INTERVALO") && !s.times().isEmpty()
            ? LocalDate.parse(s.startDate()).atTime(LocalTime.parse(s.times().get(0))).atZone(zone).toInstant()
            : LocalDate.parse(s.startDate()).atStartOfDay(zone).toInstant();
    }

    public static WeeklyCalendar calendar(Strategy s, ZoneId zone) {
        var calendar = new WeeklyCalendar(TimeZone.getTimeZone(zone));
        for (int day = 1; day <= 7; day++)
            calendar.setDayExcluded(day, !s.days().contains(Models.DAYS.get((day + 5) % 7)));
        return calendar;
    }

    /** Los mismos disparadores alimentan Quartz y la vista previa. */
    public static List<Trigger> triggers(Strategy s, ZoneId zone) {
        if (s.times().isEmpty()) return List.of();
        Date start = Date.from(startsAt(s, zone));
        if (s.frequency().equals("INTERVALO")) return List.of(TriggerBuilder.newTrigger()
            .withIdentity(s.id() + "_0").forJob(s.id()).startAt(start).modifiedByCalendar(s.id())
            .withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInHours(s.intervalHours()).repeatForever()
                .withMisfireHandlingInstructionNextWithRemainingCount()).build());
        var result = new ArrayList<Trigger>();
        for (String expression : cron(s)) result.add(TriggerBuilder.newTrigger().withIdentity(s.id() + "_" + result.size())
            .forJob(s.id()).startAt(start).withSchedule(CronScheduleBuilder.cronSchedule(expression)
                .inTimeZone(TimeZone.getTimeZone(zone)).withMisfireHandlingInstructionDoNothing()).build());
        return result;
    }

    /** Ocurrencias en (from, to], como maximo {@code limit}. */
    public static List<Instant> occurrences(Strategy s, Instant from, Instant to, ZoneId zone, int limit) {
        var result = new TreeSet<Instant>();
        if (limit <= 0 || !to.isAfter(from)) return List.of();
        Instant start = startsAt(s, zone);
        Instant begin = from.isBefore(start) ? start.minusSeconds(1) : from;
        var calendar = calendar(s, zone);
        for (var trigger : triggers(s, zone)) {
            Date next = trigger.getFireTimeAfter(Date.from(begin));
            int count = 0;
            while (next != null && !next.toInstant().isAfter(to) && count < limit) {
                if (calendar.isTimeIncluded(next.getTime())) { result.add(next.toInstant()); count++; }
                next = trigger.getFireTimeAfter(next);
            }
        }
        return result.stream().limit(limit).toList();
    }

    public static Instant next(Strategy s, Instant after, ZoneId zone) {
        Instant start = startsAt(s, zone);
        var list = occurrences(s, after, (start.isAfter(after) ? start : after).plus(Duration.ofDays(60)), zone, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Mayor separacion entre dos respaldos consecutivos en las proximas dos semanas, en horas. */
    public static long maxGapHours(Strategy s, Instant from, ZoneId zone) {
        var list = occurrences(s, from, from.plus(Duration.ofDays(15)), zone, 2000);
        if (list.size() < 2) return Long.MAX_VALUE;
        long max = 0;
        for (int i = 1; i < list.size(); i++) max = Math.max(max, Duration.between(list.get(i - 1), list.get(i)).toHours());
        return max;
    }

    public static String describe(Strategy s) {
        if (s.times().isEmpty()) return "Sin programacion";
        String days = s.days().size() == 7 ? "todos los dias" : String.join(", ", s.days().stream().map(DAY_NAMES::get).toList());
        String text = switch (s.frequency()) {
            case "INTERVALO" -> "Cada " + s.intervalHours() + " h desde las " + s.times().get(0) + ", " + days;
            case "SEMANAL" -> "Semanal (" + days + ") a las " + String.join(", ", s.times());
            default -> "Diaria a las " + String.join(", ", s.times());
        };
        return text + (s.windowMinutes() == null ? "" : " | ventana " + s.windowMinutes() + " min");
    }
}
