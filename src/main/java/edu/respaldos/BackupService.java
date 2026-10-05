package edu.respaldos;

import edu.respaldos.Models.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

/**
 * Flujo del enunciado: configuracion -> validacion -> script -> visualizacion -> aprobacion ->
 * programacion -> ejecucion -> evidencia -> alertas.
 */
public final class BackupService implements AutoCloseable {
    public static final String ZONE = "America/Costa_Rica";
    public static final ZoneId ZONE_ID = ZoneId.of(ZONE);
    final Catalog catalog;
    final Path runtime;
    private final Rman rman;
    private final Notifier notifier;
    private final ScriptStore scripts;
    private final Scheduler scheduler;
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final Map<String, String> active = new ConcurrentHashMap<>();

    public BackupService(Catalog catalog, Path runtime, Rman rman) throws Exception { this(catalog, runtime, rman, Notifier.NONE); }

    public BackupService(Catalog catalog, Path runtime, Rman rman, Notifier notifier) throws Exception {
        this.catalog = catalog; this.runtime = runtime; this.rman = rman; this.notifier = notifier;
        this.scripts = new ScriptStore(runtime.resolve("scripts"));
        // Un proceso Java detenido no puede saber si el RMAN que lanzo tambien se detuvo.
        for (var e : catalog.executions()) {
            if (List.of("EJECUTANDO", "INCIERTO").contains(e.status())) {
                active.put(e.databaseId(), e.id());
                if (e.status().equals("EJECUTANDO"))
                    catalog.update(e.finish("INCIERTO", null, "Proceso anterior sin cierre confirmado. Comprueba RMAN antes de liberar esta base.", e.pieces(), e.details()));
            }
        }
        var props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "GestorRMAN");
        props.setProperty("org.quartz.threadPool.threadCount", "2");
        props.setProperty("org.quartz.threadPool.makeThreadsDaemons", "true");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        scheduler = new StdSchedulerFactory(props).getScheduler();
        scheduler.getContext().put("service", this);
        for (var s : catalog.strategies()) schedule(s);
        scheduler.start();
    }

    // ---------- Construccion y aprobacion ----------

    public String script(Strategy s) throws Exception { return RmanScript.backup(s, catalog.database(s.databaseId())); }

    public Map<String, Object> preview(Strategy s) throws Exception {
        var db = catalog.database(s.databaseId());
        var issues = RmanScript.check(s, catalog.status(db.id()).orElse(null));
        boolean valid = issues.stream().noneMatch(i -> i.level().equals("ERROR"));
        String script = valid ? RmanScript.backup(s, db) : "";
        var now = Instant.now();
        return Map.of("valid", valid, "script", script, "validateScript", valid ? RmanScript.validate(s) : "", "hash", valid ? RmanScript.hash(script) : "",
            "issues", issues, "schedule", Schedules.describe(s),
            "nextRuns", s.times().isEmpty() ? List.of() : Schedules.occurrences(s, now, now.plus(Duration.ofDays(14)), ZONE_ID, 5).stream().map(Instant::toString).toList());
    }

    public boolean approved(Strategy s) throws Exception {
        var approval = catalog.approval(s.id());
        return approval.isPresent() && approval.get().scriptHash().equals(RmanScript.hash(script(s)));
    }

    public synchronized Map<String, Object> save(Strategy s) throws Exception {
        catalog.database(s.databaseId());
        Models.require(!active.containsKey(s.databaseId()), "Hay una ejecucion activa en esta base.");
        boolean existed = catalog.strategies().stream().anyMatch(o -> o.id().equals(s.id()));
        catalog.save(s);
        event(existed ? "ESTRATEGIA_EDITADA" : "ESTRATEGIA_CREADA", s, s.name() + (approved(s) ? "" : " | requiere aprobacion del script"));
        schedule(s);
        return Map.of("strategy", s, "approved", approved(s));
    }

    public synchronized Approval approve(String strategyId, String approvedBy) throws Exception {
        var s = catalog.strategy(strategyId);
        Models.require(!active.containsKey(s.databaseId()), "Espera a que termine la ejecucion antes de aprobar.");
        var errors = RmanScript.check(s, diagnose(s.databaseId())).stream().filter(i -> i.level().equals("ERROR")).toList();
        if (!errors.isEmpty()) throw new IllegalArgumentException("No se puede aprobar: " + errors.get(0).message());
        String who = approvedBy == null || approvedBy.isBlank() ? Optional.ofNullable(s.responsible()).orElse("Administrador") : approvedBy.trim();
        Models.require(who.length() <= 80, "Nombre de aprobador demasiado largo.");
        var approval = new Approval(s.id(), RmanScript.hash(script(s)), Instant.now().toString(), who);
        catalog.save(approval);
        event("SCRIPT_APROBADO", s, "Aprobado por " + who + " | hash " + approval.scriptHash());
        schedule(s);
        return approval;
    }

    public synchronized void delete(String id) throws Exception {
        var s = catalog.strategy(id);
        Models.require(!active.containsKey(s.databaseId()), "Espera a que termine la ejecucion.");
        scheduler.deleteJob(new JobKey(id)); scripts.delete(s, catalog.database(s.databaseId())); catalog.deleteStrategy(id);
        event("ESTRATEGIA_ELIMINADA", s, s.name() + " | el historial se conserva");
    }

    /** Aplica una recomendacion por decision del administrador; nunca se aplica sola. */
    public synchronized Map<String, Object> apply(String strategyId, String action) throws Exception {
        var s = catalog.strategy(strategyId);
        var changed = switch (action) {
            case "AGREGAR_ARCHIVELOGS" -> s.withArchivelogs(true);
            case "ACTIVAR_VERIFICACION" -> s.withVerifyAfter(true);
            default -> throw new IllegalArgumentException("Recomendacion no reconocida.");
        };
        Models.require(!active.containsKey(s.databaseId()), "Hay una ejecucion activa en esta base.");
        catalog.save(changed);
        event("RECOMENDACION_APLICADA", changed, action + " | el script cambio y debe aprobarse de nuevo");
        schedule(changed);
        return Map.of("strategy", changed, "approved", approved(changed));
    }

    // ---------- Programacion ----------

    private void schedule(Strategy s) throws Exception {
        scheduler.deleteJob(new JobKey(s.id()));
        var db = catalog.database(s.databaseId());
        scripts.write(s, db, RmanScript.backup(s, db)); // el .rma de la estrategia refleja siempre su configuracion vigente
        if (!s.enabled() || s.times().isEmpty() || !approved(s)) return;
        var job = JobBuilder.newJob(ScheduledBackup.class).withIdentity(s.id()).usingJobData("strategyId", s.id()).storeDurably().build();
        scheduler.addJob(job, true);
        scheduler.addCalendar(s.id(), Schedules.calendar(s, ZONE_ID), true, true);
        for (var trigger : Schedules.triggers(s, ZONE_ID)) scheduler.scheduleJob(trigger);
    }

    public static final class ScheduledBackup implements Job {
        public void execute(JobExecutionContext context) throws JobExecutionException {
            try {
                var service = (BackupService) context.getScheduler().getContext().get("service");
                service.run(context.getMergedJobDataMap().getString("strategyId"), "BACKUP", "HORARIO", context.getScheduledFireTime().toInstant().toString());
            } catch (Exception e) { throw new JobExecutionException(e, false); }
        }
    }

    // ---------- Ejecucion y evidencia ----------

    public synchronized String run(String strategyId, String operation, String source, String planned) throws Exception {
        Models.require(List.of("BACKUP", "VALIDATE").contains(operation), "Operacion no valida.");
        var s = catalog.strategy(strategyId); var db = catalog.database(s.databaseId());
        String coverage = RmanScript.coverageHash(s, db);
        var target = operation.equals("VALIDATE") ? ExecutionEvidence.current(catalog.executions(), s, coverage).stream()
            .filter(e -> e.operation().equals("BACKUP") && e.succeeded() && !e.evidence().backupSets().isEmpty()).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No hay un respaldo de este alcance con conjuntos identificados. Ejecuta uno nuevo; el historial anterior se conserva.")) : null;
        if (operation.equals("BACKUP") && !approved(s)) {
            Models.require(!source.equals("MANUAL"), "Revisa y aprueba el script antes de ejecutarlo.");
            throw new IllegalStateException("Script no aprobado.");
        }
        String id = UUID.randomUUID().toString();
        String occurrence = source.equals("HORARIO") ? s.id() + "|" + planned : null;
        String type = operation.equals("BACKUP") ? RmanScript.how(s) + " | " + RmanScript.what(s) : "Verificacion VALIDATE BACKUPSET | " + RmanScript.what(s);
        var execution = new Execution(id, s.id(), s.name(), db.id(), db.name(), operation, type, source, planned, Instant.now().toString(), null,
            "EJECUTANDO", null, "Validando antes de ejecutar RMAN.", s.destination(), List.of(), List.of(),
            target == null ? catalog.approval(s.id()).orElseThrow().scriptHash() : RmanScript.hash(RmanScript.validateSets(target.evidence().backupSets())),
            new Evidence(coverage, target == null ? List.of() : target.evidence().handles(),
                target == null ? List.of() : target.evidence().backupSets(), target == null ? null : target.id(), false,
                target == null ? List.of() : target.evidence().datafiles()));
        if (active.containsKey(db.id())) {
            if (!source.equals("HORARIO")) throw new IllegalStateException("Esta base tiene una ejecucion activa o sin cierre confirmado.");
            catalog.insert(execution.finish("OMITIDO", null, "Otra ejecucion ocupaba esta base. No se inicio RMAN.", List.of(), List.of()), occurrence);
            return id;
        }
        Path dir = runtime.resolve("executions").resolve(id);
        Files.createDirectories(dir);
        if (!catalog.insert(execution, occurrence)) return "duplicada";
        active.put(db.id(), id);
        workers.submit(() -> {
            boolean uncertain = false;
            var checkedExecution = execution;
            try {
                var status = diagnose(db.id());
                var errors = operation.equals("BACKUP") ? RmanScript.check(s, status).stream().filter(i -> i.level().equals("ERROR")).toList()
                    : !status.reachable() || !status.rmanAvailable() ? List.of(new Issue("ERROR", "SIN_CONEXION", "Oracle o RMAN no estan disponibles.")) : List.<Issue>of();
                if (!errors.isEmpty()) {
                    record(execution.finish("FALLIDO", null, "Validacion previa: " + errors.get(0).message(), List.of(), errors.stream().map(Issue::message).toList()));
                    return;
                }
                if (target != null) {
                    var currentKeys = rman.backupSets(db, target.evidence().handles(), dir.resolve("sets-check.log"));
                    Models.require(new TreeSet<>(currentKeys).equals(new TreeSet<>(target.evidence().backupSets())), "El catalogo RMAN cambio: los conjuntos ya no corresponden al respaldo elegido.");
                }
                String script = target == null ? RmanScript.backup(s, db) : RmanScript.validateSets(target.evidence().backupSets());
                Models.require(RmanScript.hash(script).equals(execution.scriptHash()), "El script no corresponde a la aprobacion.");
                if (target == null) checkedExecution = execution.withEvidence(new Evidence(coverage, List.of(), List.of(), null, false,
                    RmanScript.selectedDatafiles(s, status)));
                catalog.update(checkedExecution);
                Files.writeString(dir.resolve("script" + Rman.SCRIPT_EXT), script);
                var outcome = evaluate(s, db, operation, checkedExecution, rman.execute(db, script, dir.resolve("output.log"), Duration.ofHours(4)), dir);
                uncertain = outcome.status().equals("INCIERTO");
                try { record(outcome); }
                catch (Exception persistenceError) { uncertain = true; throw persistenceError; }
            } catch (Exception e) {
                uncertain = uncertain || e instanceof InterruptedException;
                try { record(checkedExecution.finish(uncertain ? "INCIERTO" : "FALLIDO", null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), List.of(), List.of())); }
                catch (Exception persistenceError) { uncertain = true; System.err.println("No se pudo guardar el resultado de " + id); }
            } finally { if (!uncertain) active.remove(db.id(), id); }
        });
        return id;
    }

    /** Guarda el resultado final de una ejecucion y la comunica al notificador; un aviso fallido nunca cambia el resultado. */
    private void record(Execution finished) throws Exception {
        catalog.update(finished);
        try { notifier.notifyFinished(finished); }
        catch (RuntimeException e) { System.err.println("No se pudo avisar la ejecucion " + finished.id() + ": " + e); }
    }

    /**
     * Un codigo de salida 0 no basta (enunciado 14.7): se exigen las piezas en disco y, si la
     * estrategia lo pide, una verificacion VALIDATE BACKUPSET posterior.
     */
    private Execution evaluate(Strategy s, Database db, String operation, Execution e, Rman.Result result, Path dir) throws Exception {
        if (result.timedOut())
            return e.finish("INCIERTO", null, "Tiempo de espera agotado. Comprueba el proceso RMAN en Oracle; la base queda bloqueada hasta liberarla.", List.of(), List.of());
        var errors = Rman.errors(result.output());
        if (!Rman.successful(result)) {
            String first = errors.stream().filter(l -> !l.startsWith("RMAN-03002") && !l.startsWith("RMAN-03009")).findFirst()
                .orElse(errors.isEmpty() ? lastLine(result.output()) : errors.get(0));
            return e.finish("FALLIDO", result.code(), "RMAN informo un error: " + first, List.of(), errors);
        }
        var details = new ArrayList<String>(Rman.warnings(result.output()));
        if (operation.equals("VALIDATE"))
            return e.withEvidence(e.evidence().verified(details.isEmpty())).finish(details.isEmpty() ? "EXITOSO" : "CON_ADVERTENCIAS", result.code(),
                "RMAN leyo los conjuntos del respaldo " + e.evidence().verifies() + ". No sustituye una prueba de recuperacion real.", List.of(), details);

        var pieces = Rman.pieces(result.output());
        var files = rman.checkFiles(db, pieces, dir.resolve("files.log"));
        var evidence = new ArrayList<String>();
        files.existing().forEach((path, size) -> evidence.add(path + " (" + RmanScript.human(size) + ")"));
        if (pieces.isEmpty()) details.add("RMAN no informo piezas de respaldo en su salida.");
        if (!files.checked()) details.add("No se pudo comprobar la existencia de las piezas en el servidor.");
        if (files.checked() && !files.missing().isEmpty())
            return e.finish("FALLIDO", result.code(), "RMAN termino, pero faltan " + files.missing().size() + " archivo(s) de respaldo en el destino.", pieces, concat(details, files.missing().stream().map(f -> "No existe: " + f).toList()));
        if (files.existing().values().stream().anyMatch(size -> size <= 0))
            return e.finish("FALLIDO", result.code(), "Hay piezas vacias; no se considera un respaldo correcto.", pieces, details);
        List<Long> sets = List.of();
        try {
            Models.require(!result.output().startsWith("[Fragmento final"), "El registro esta truncado: no se puede identificar la totalidad de las piezas.");
            sets = rman.backupSets(db, pieces, dir.resolve("sets.log"));
        } catch (Exception lookupError) {
            if (lookupError instanceof InterruptedException) throw lookupError;
            details.add("Conjuntos no identificados: " + lookupError.getMessage());
        }
        e = e.withEvidence(new Evidence(e.evidence().coverageHash(), pieces, sets, null, false, e.evidence().datafiles()));

        if (s.verifyAfter()) {
            if (sets.isEmpty()) return e.finish("FALLIDO", result.code(), "El respaldo se genero, pero no se pudieron identificar sus conjuntos para verificarlo.", evidence, details);
            String verify = RmanScript.validateSets(sets);
            Files.writeString(dir.resolve("verify" + Rman.SCRIPT_EXT), verify);
            var check = rman.execute(db, verify, dir.resolve("verify.log"), Duration.ofHours(2));
            if (check.timedOut()) return e.finish("INCIERTO", null, "La verificacion no confirmo su final. Comprueba RMAN antes de liberar la base.", evidence, details);
            if (!Rman.successful(check)) {
                var verr = Rman.errors(check.output());
                return e.finish("FALLIDO", check.code(), "El respaldo se genero, pero VALIDATE BACKUPSET fallo" + (verr.isEmpty() ? "." : ": " + verr.get(0)), evidence, concat(details, verr));
            }
            var warnings = Rman.warnings(check.output());
            details.addAll(warnings);
            e = e.withEvidence(e.evidence().verified(warnings.isEmpty()));
            if (warnings.isEmpty()) details.add("Verificacion posterior correcta: conjuntos " + sets + " leidos por VALIDATE BACKUPSET.");
        }
        var elapsed = Duration.between(Instant.parse(e.startedAt()), Instant.now());
        if (s.windowMinutes() != null && elapsed.compareTo(Duration.ofMinutes(s.windowMinutes())) > 0)
            details.add("La ejecucion, incluida la verificacion, duro " + elapsed.toSeconds() + " s y excedio la duracion maxima de " + s.windowMinutes() + " min.");
        boolean warned = details.stream().anyMatch(d -> !d.startsWith("Verificacion posterior correcta"));
        return e.finish(warned ? "CON_ADVERTENCIAS" : "EXITOSO", result.code(),
            (warned ? "Respaldo realizado con advertencias. " : "Respaldo realizado. ") + files.existing().size() + " pieza(s) comprobadas en " + s.destination() + ".", evidence, details);
    }

    /** Libera una base bloqueada por una ejecucion incierta, despues de que el administrador lo comprobo. */
    public synchronized void release(String databaseId) throws Exception {
        String executionId = active.get(databaseId);
        Models.require(executionId != null, "La base no esta bloqueada.");
        var e = catalog.execution(executionId);
        Models.require(e.status().equals("INCIERTO"), "La ejecucion sigue en curso; espera a que termine.");
        catalog.update(e.finish("FALLIDO", e.exitCode(), "Sin cierre confirmado; el administrador libero la base. " + e.message(), e.pieces(), e.details()));
        active.remove(databaseId, executionId);
        event("BASE_LIBERADA", catalog.strategies().stream().filter(s -> s.id().equals(e.strategyId())).findFirst().orElse(null), "Base liberada tras ejecucion incierta " + executionId);
    }

    public DatabaseStatus diagnose(String id) throws Exception {
        var db = catalog.database(id);
        var destinations = new TreeSet<String>(List.of(Models.DEFAULT_DESTINATION));
        catalog.strategies().stream().filter(s -> s.databaseId().equals(id)).forEach(s -> destinations.add(s.destination()));
        var status = rman.diagnose(db, runtime.resolve("diagnostics").resolve(id), destinations);
        catalog.save(status);
        catalog.log(new Event(UUID.randomUUID().toString(), Instant.now().toString(), "BASE_COMPROBADA", id, null, db.name() + " | " + status.message()));
        return status;
    }

    // ---------- Estado para la interfaz ----------

    public Map<String, Object> state() throws Exception {
        var now = Instant.now();
        var databases = catalog.databases();
        var strategies = catalog.strategies();
        var executions = catalog.executions();
        var statuses = new HashMap<String, DatabaseStatus>();
        catalog.statuses().forEach(st -> statuses.put(st.databaseId(), st));
        var approvals = new HashMap<String, Approval>();
        catalog.approvals().forEach(a -> approvals.put(a.strategyId(), a));
        var occurrences = new HashMap<String, Set<String>>();
        var scripts = new HashMap<String, String>();
        var views = new ArrayList<Map<String, Object>>();
        var upcoming = new ArrayList<Map<String, String>>();
        var missed = new ArrayList<Map<String, String>>();
        for (var s : strategies) {
            occurrences.put(s.id(), catalog.occurrences(s.id()));
            var db = databases.stream().filter(d -> d.id().equals(s.databaseId())).findFirst().orElse(null);
            String script = db == null ? "" : RmanScript.backup(s, db);
            scripts.put(s.id(), script);
            var approval = approvals.get(s.id());
            boolean isApproved = approval != null && approval.scriptHash().equals(RmanScript.hash(script));
            boolean scheduled = isApproved && s.enabled() && !s.times().isEmpty();
            Instant next = scheduled ? Schedules.next(s, now, ZONE_ID) : null;
            if (scheduled) {
                for (var t : Schedules.occurrences(s, now, now.plus(Duration.ofHours(24)), ZONE_ID, 48))
                    upcoming.add(Map.of("strategyId", s.id(), "at", t.toString()));
                Instant from = Instant.parse(approval.approvedAt()).isAfter(now.minus(Duration.ofHours(24))) ? Instant.parse(approval.approvedAt()) : now.minus(Duration.ofHours(24));
                var seen = occurrences.get(s.id());
                for (var t : Schedules.occurrences(s, from, now.minus(Duration.ofMinutes(2)), ZONE_ID, 48))
                    if (!seen.contains(s.id() + "|" + t)) missed.add(Map.of("strategyId", s.id(), "at", t.toString()));
            }
            var view = new LinkedHashMap<String, Object>();
            view.put("strategy", s);
            view.put("tag", s.tag());
            view.put("what", RmanScript.what(s));
            view.put("how", RmanScript.how(s));
            view.put("schedule", Schedules.describe(s));
            view.put("approved", isApproved);
            view.put("approval", approval);
            view.put("scheduled", scheduled);
            view.put("nextRun", next == null ? null : next.toString());
            view.put("rpoHours", Models.rpoHours(s.priority()));
            String coverage = db == null ? "" : RmanScript.coverageHash(s, db);
            var current = ExecutionEvidence.current(executions, s, coverage);
            view.put("coverageHash", coverage);
            view.put("verified", current.stream().filter(e -> e.operation().equals("BACKUP") && e.succeeded()).findFirst()
                .map(e -> ExecutionEvidence.verified(e, current)).orElse(false));
            views.add(view);
        }
        var alerts = Alerts.evaluate(new Alerts.Input(databases, statuses, strategies, approvals,
            st -> scripts.getOrDefault(st.id(), ""), executions, occurrences, Map.copyOf(active), now, ZONE_ID));
        var result = new LinkedHashMap<String, Object>();
        result.put("databases", databases);
        result.put("statuses", statuses);
        result.put("strategies", views);
        result.put("executions", executions);
        result.put("alerts", alerts);
        result.put("upcoming", upcoming);
        result.put("missed", missed);
        result.put("events", catalog.events());
        result.put("active", Map.copyOf(active));
        result.put("mode", rman instanceof SimulatedRman ? "SIMULACION" : "LOCAL");
        result.put("timezone", ZONE);
        result.put("serverTime", now.toString());
        return result;
    }

    private void event(String type, Strategy s, String detail) throws Exception {
        catalog.log(new Event(UUID.randomUUID().toString(), Instant.now().toString(), type, s == null ? null : s.databaseId(), s == null ? null : s.id(), detail));
    }

    private static List<String> concat(List<String> a, List<String> b) { var l = new ArrayList<>(a); l.addAll(b); return l; }

    private static String lastLine(String output) {
        var lines = output.strip().split("\\R");
        return lines.length == 0 ? "sin salida" : lines[lines.length - 1].trim();
    }

    public void close() throws Exception { scheduler.shutdown(false); workers.shutdown(); notifier.close(); }
}
