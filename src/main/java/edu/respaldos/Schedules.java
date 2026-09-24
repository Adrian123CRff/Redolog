package edu.respaldos;

import edu.respaldos.Models.*;
import java.time.*;
import java.util.*;
import org.quartz.CronExpression;

/** Traduce el "CUÁNDO" de una estrategia a expresiones cron de Quartz y calcula sus ocurrencias. */
public final class Schedules {
    private Schedules() {}
    private static final Map<String, String> DAY_NAMES = Map.of("MON", "lun", "TUE", "mar", "WED", "mie", "THU", "jue", "FRI", "vie", "SAT", "sab", "SUN", "dom");

    public static List<String> cron(Strategy s) {
        String days = String.join(",", s.days());
        return s.times().stream().map(LocalTime::parse).map(t -> s.frequency().equals("INTERVALO")
            ? "0 " + t.getMinute() + " " + t.getHour() + "/" + s.intervalHours() + " ? * " + days
            : "0 " + t.getMinute() + " " + t.getHour() + " ? * " + days).toList();
    }

    public static Instant startsAt(Strategy s, ZoneId zone) {
        return LocalDate.parse(s.startDate()).atStartOfDay(zone).toInstant();
    }

    /** Ocurrencias en (from, to], como maximo {@code limit}. */
    public static List<Instant> occurrences(Strategy s, Instant from, Instant to, ZoneId zone, int limit) {
        var result = new TreeSet<Instant>();
        Instant start = startsAt(s, zone);
        Instant begin = from.isBefore(start) ? start.minusSeconds(1) : from;
        for (String expression : cron(s)) {
            try {
                var cron = new CronExpression(expression);
                cron.setTimeZone(TimeZone.getTimeZone(zone));
                Date next = cron.getTimeAfter(Date.from(begin));
                while (next != null && !next.toInstant().isAfter(to) && result.size() < limit * 2) {
                    result.add(next.toInstant());
                    next = cron.getTimeAfter(next);
                }
            } catch (java.text.ParseException e) { throw new IllegalStateException("Expresion cron invalida: " + expression, e); }
        }
        return result.stream().limit(limit).toList();
    }

    public static Instant next(Strategy s, Instant after, ZoneId zone) {
        var list = occurrences(s, after, after.plus(Duration.ofDays(60)), zone, 1);
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
