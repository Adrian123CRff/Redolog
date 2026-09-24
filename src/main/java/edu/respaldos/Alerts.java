package edu.respaldos;

import edu.respaldos.Models.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/**
 * Control preventivo: evalua el estado del catalogo y produce alertas antes de que un fallo
 * de respaldo se convierta en una perdida de datos. Es una funcion pura de sus entradas.
 */
public final class Alerts {
    private Alerts() {}
    public static final List<String> LEVELS = List.of("ALERTA", "ADVERTENCIA", "RECOMENDACION", "INFORMATIVA");

    public record Input(List<Database> databases, Map<String, DatabaseStatus> statuses, List<Strategy> strategies,
                        Map<String, Approval> approvals, Function<Strategy, String> scriptOf, List<Execution> executions,
                        Map<String, Set<String>> occurrences, Map<String, String> active, Instant now, ZoneId zone) {}

    public static List<Alert> evaluate(Input in) {
        var alerts = new ArrayList<Alert>();
        for (var db : in.databases()) database(in, db, alerts);
        for (var s : in.strategies()) strategy(in, s, alerts);
        alerts.sort(Comparator.comparingInt(a -> LEVELS.indexOf(a.level())));
        return alerts;
    }

    private static void database(Input in, Database db, List<Alert> alerts) {
        var st = in.statuses().get(db.id());
        if (st == null) {
            alerts.add(new Alert("INFORMATIVA", "BASE_SIN_COMPROBAR", db.name() + ": sin comprobar", "Comprueba la conexion para conocer el modo de archivado, los tablespaces y el espacio en destino.", db.id(), null, "COMPROBAR", "Comprobar"));
        } else if (!st.reachable()) {
            alerts.add(new Alert("ALERTA", "BASE_SIN_CONEXION", db.name() + ": sin conexion", "La ultima comprobacion fallo: " + st.message(), db.id(), null, "COMPROBAR", "Reintentar"));
        } else if ("NOARCHIVELOG".equals(st.logMode())) {
            alerts.add(new Alert("ADVERTENCIA", "NOARCHIVELOG", db.name() + ": modo NOARCHIVELOG", "La base de datos se encuentra en modo NOARCHIVELOG. Las posibilidades de recuperacion son mas limitadas. Revise la estrategia de respaldo y los requerimientos de recuperacion antes de continuar.", db.id(), null, null, null));
        } else if ("ARCHIVELOG".equals(st.logMode())) {
            alerts.add(new Alert("INFORMATIVA", "ARCHIVELOG", db.name() + ": modo ARCHIVELOG", "Admite respaldos en linea y recuperacion hasta un punto en el tiempo.", db.id(), null, null, null));
            var own = in.strategies().stream().filter(s -> s.databaseId().equals(db.id()) && s.enabled()).toList();
            if (!own.isEmpty() && own.stream().noneMatch(Strategy::archivelogs))
                alerts.add(new Alert("ADVERTENCIA", "SIN_RESPALDO_ARCHIVELOGS", db.name() + ": ningun respaldo de archived redo logs", "Ninguna estrategia activa respalda los archived redo logs. Sin ellos no se puede recuperar hasta un punto posterior al ultimo respaldo.", db.id(), null, null, null));
        }
        String busy = in.active().get(db.id());
        if (busy != null) in.executions().stream().filter(e -> e.id().equals(busy) && e.status().equals("INCIERTO")).findFirst().ifPresent(e ->
            alerts.add(new Alert("ALERTA", "EJECUCION_INCIERTA", db.name() + ": ejecucion sin cierre confirmado", "La ejecucion de " + e.strategyName() + " no confirmo su final. Verifica en Oracle que RMAN termino y libera la base.", db.id(), e.strategyId(), "LIBERAR", "Liberar base")));
    }

    private static void strategy(Input in, Strategy s, List<Alert> alerts) {
        var st = in.statuses().get(s.databaseId());
        var approval = in.approvals().get(s.id());
        boolean approved = approval != null && approval.scriptHash().equals(RmanScript.hash(in.scriptOf().apply(s)));
        String n = s.name();

        if (!s.enabled()) alerts.add(new Alert("ADVERTENCIA", "INACTIVA", n + ": estrategia inactiva", "No se ejecutara automaticamente mientras este inactiva.", s.databaseId(), s.id(), "EDITAR", "Editar"));
        if (s.times().isEmpty()) alerts.add(new Alert("ADVERTENCIA", "SIN_PROGRAMACION", n + ": sin programacion", "No tiene horarios definidos.", s.databaseId(), s.id(), "EDITAR", "Editar"));
        if (!approved) alerts.add(new Alert("ADVERTENCIA", "SIN_APROBAR", n + ": script pendiente de aprobacion",
            approval == null ? "El administrador debe revisar y aprobar el script antes de programarlo." : "La configuracion cambio despues de la aprobacion; el script nuevo debe aprobarse otra vez.", s.databaseId(), s.id(), "APROBAR", "Revisar y aprobar"));

        for (var issue : RmanScript.check(s, st)) {
            switch (issue.code()) {
                case "SIN_RESPONSABLE", "SIN_DESCRIPCION" -> alerts.add(new Alert("ADVERTENCIA", "INCOMPLETA", n + ": configuracion incompleta", issue.message(), s.databaseId(), s.id(), "EDITAR", "Completar"));
                case "TABLESPACE_INEXISTENTE", "DATAFILE_INEXISTENTE", "DESTINO_INEXISTENTE", "ARCHIVELOG_SIN_MODO" ->
                    alerts.add(new Alert("ALERTA", issue.code(), n + ": configuracion invalida", issue.message(), s.databaseId(), s.id(), "EDITAR", "Corregir"));
                case "FRECUENCIA_INSUFICIENTE" -> alerts.add(new Alert("ADVERTENCIA", issue.code(), n + ": frecuencia insuficiente para su prioridad", issue.message(), s.databaseId(), s.id(), "EDITAR", "Ajustar horario"));
                case "ESPACIO_INSUFICIENTE" -> alerts.add(new Alert("ADVERTENCIA", issue.code(), n + ": espacio insuficiente en destino", issue.message(), s.databaseId(), s.id(), null, null));
                case "SIN_ARCHIVED_LOGS" -> alerts.add(new Alert("ADVERTENCIA", issue.code(), n + ": sin archived redo logs disponibles", issue.message(), s.databaseId(), s.id(), null, null));
                case "INCLUIR_ARCHIVELOGS" -> { if (!s.priority().equals("BAJA"))
                    alerts.add(new Alert("RECOMENDACION", issue.code(), n + ": incorporar archived redo logs", issue.message(), s.databaseId(), s.id(), "AGREGAR_ARCHIVELOGS", "Aplicar recomendacion")); }
                default -> {}
            }
        }

        var mine = in.executions().stream().filter(e -> s.id().equals(e.strategyId())).toList();
        var backups = mine.stream().filter(e -> e.operation().equals("BACKUP") && !e.status().equals("EJECUTANDO")).toList();
        if (!backups.isEmpty()) {
            var last = backups.get(0);
            if (last.status().equals("FALLIDO"))
                alerts.add(new Alert("ALERTA", "EJECUCION_FALLIDA", n + ": ultima ejecucion fallida", last.message(), s.databaseId(), s.id(), "VER:" + last.id(), "Ver evidencia"));
            if (last.status().equals("CON_ADVERTENCIAS"))
                alerts.add(new Alert("ADVERTENCIA", "EJECUCION_CON_ADVERTENCIAS", n + ": ultima ejecucion con advertencias", last.message(), s.databaseId(), s.id(), "VER:" + last.id(), "Ver evidencia"));
        }

        if (s.enabled() && approved && !s.times().isEmpty()) {
            // Respaldos programados que no se ejecutaron (aplicacion detenida, base ocupada, etc.).
            Instant from = max(in.now().minus(Duration.ofDays(7)), Instant.parse(approval.approvedAt()));
            var expected = Schedules.occurrences(s, from, in.now().minus(Duration.ofMinutes(2)), in.zone(), 500);
            var seen = in.occurrences().getOrDefault(s.id(), Set.of());
            var omitted = mine.stream().filter(e -> e.status().equals("OMITIDO")).map(e -> s.id() + "|" + e.plannedAt()).toList();
            var missed = expected.stream().filter(t -> !seen.contains(s.id() + "|" + t) || omitted.contains(s.id() + "|" + t)).toList();
            if (!missed.isEmpty())
                alerts.add(new Alert("ALERTA", "NO_EJECUTADA", n + ": " + missed.size() + (missed.size() == 1 ? " respaldo programado no se ejecuto" : " respaldos programados no se ejecutaron"),
                    "Ultimo caso: " + local(missed.get(missed.size() - 1), in.zone()) + ". Revisa que la aplicacion estuviera en ejecucion y la base libre.", s.databaseId(), s.id(), "EJECUTAR", "Ejecutar ahora"));

            // Sin respaldo reciente segun el objetivo de la prioridad (la frecuencia se valida en RmanScript.check).
            int rpo = Models.rpoHours(s.priority());
            var lastOk = backups.stream().filter(Execution::succeeded).findFirst();
            Instant reference = lastOk.map(e -> Instant.parse(e.finishedAt())).orElse(max(Instant.parse(approval.approvedAt()), Schedules.startsAt(s, in.zone())));
            long hours = Duration.between(reference, in.now()).toHours();
            if (hours > rpo)
                alerts.add(new Alert("ALERTA", "SIN_RESPALDO_RECIENTE", n + ": sin respaldo reciente",
                    (lastOk.isPresent() ? "El ultimo respaldo correcto tiene " + hours + " h" : "No hay respaldos correctos desde hace " + hours + " h") + "; objetivo de prioridad " + s.priority().toLowerCase() + ": " + rpo + " h.", s.databaseId(), s.id(), "EJECUTAR", "Ejecutar ahora"));

            // Ventana de respaldo excedida.
            if (s.windowMinutes() != null && !backups.isEmpty() && backups.get(0).finishedAt() != null) {
                long minutes = Duration.between(Instant.parse(backups.get(0).startedAt()), Instant.parse(backups.get(0).finishedAt())).toMinutes();
                if (minutes > s.windowMinutes())
                    alerts.add(new Alert("ADVERTENCIA", "VENTANA_EXCEDIDA", n + ": ventana de respaldo excedida", "La ultima ejecucion duro " + minutes + " min; la ventana es de " + s.windowMinutes() + " min.", s.databaseId(), s.id(), null, null));
            }
        }

        if (List.of("LEVEL1", "CUMULATIVE").contains(s.method()) && in.strategies().stream().noneMatch(o -> o.databaseId().equals(s.databaseId())
                && o.method().equals("LEVEL0") && o.enabled() && (o.scope().equals("DATABASE") || (o.scope().equals(s.scope()) && o.tablespaces().containsAll(s.tablespaces()) && o.datafiles().containsAll(s.datafiles())))))
            alerts.add(new Alert("RECOMENDACION", "SIN_NIVEL0", n + ": falta la base de la cadena incremental", "Programa una estrategia incremental nivel 0 que cubra el mismo alcance. Un respaldo FULL no sirve como base de un nivel 1.", s.databaseId(), s.id(), null, null));

        // Verificacion de recuperabilidad: un respaldo que no se ha leido no esta comprobado.
        var lastBackupOk = backups.stream().filter(Execution::succeeded).findFirst();
        if (lastBackupOk.isPresent() && !s.verifyAfter()) {
            var lastValidation = mine.stream().filter(e -> e.operation().equals("VALIDATE") && e.succeeded()).findFirst();
            if (lastValidation.isEmpty() || lastValidation.get().startedAt().compareTo(lastBackupOk.get().finishedAt()) < 0)
                alerts.add(new Alert("RECOMENDACION", "SIN_VERIFICAR", n + ": verificar el ultimo respaldo", "El ultimo respaldo no se ha verificado con RESTORE ... VALIDATE. Que RMAN termine sin errores no garantiza que se pueda restaurar.", s.databaseId(), s.id(), "VERIFICAR", "Verificar ahora"));
        }
    }

    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }

    private static String local(Instant instant, ZoneId zone) {
        return java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").format(instant.atZone(zone));
    }
}
