package edu.respaldos;

import edu.respaldos.Models.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Ejecutor de estrategias, programa aparte de la interfaz (pedido del profesor, clase del 05/10):
 * abre el catalogo plano, lo recorre linea por linea, revisa dia y hora de cada estrategia y, cuando
 * toca, invoca RMAN con su script .rma pidiendole el LOG. Despues actualiza el catalogo con las piezas
 * y el log, sigue con la siguiente linea y, al llegar al final, vuelve a empezar.
 *
 * Uso:  java -cp target/gestor-rman-0.1.0.jar edu.respaldos.Ejecutor [--datos runtime] [--ahora RMA0001]
 */
public final class Ejecutor {
    public static final String RESULTS = "resultados.txt";
    public static final List<String> RESULT_COLUMNS = List.of("ID", "CODIGO", "ESTRATEGIA_ID", "ORIGEN", "PROGRAMADA", "INICIO", "FIN",
        "RESULTADO", "CODIGO_SALIDA", "HUELLA", "PIEZAS", "LOG", "MENSAJE", "DETALLES");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** Invoca RMAN dentro del contenedor con el script dado y deja el LOG que genera RMAN en {@code log}. */
    public interface Runner { Rman.Result run(String container, String code, String script, Path log) throws Exception; }

    private final Path runtime;
    private final Runner runner;
    private final ZoneId zone;

    public Ejecutor(Path runtime, Runner runner, ZoneId zone) { this.runtime = runtime; this.runner = runner; this.zone = zone; }

    public Path directory() { return runtime.resolve("ejecutor"); }

    /** Estrategias que tocan en (from, to], de mayor a menor prioridad; ejecuta cada una y devuelve sus resultados. */
    public List<Map<String, String>> tick(Instant from, Instant to) throws Exception {
        var due = new ArrayList<Map.Entry<Map<String, String>, Instant>>();
        for (var row : FlatCatalog.read(runtime).rows()) {
            if (!"SI".equals(row.get("ACTIVA")) || !"SI".equals(row.get("APROBADA"))) continue;
            try { for (var at : Schedules.occurrences(schedule(row), from, to, zone, 20)) due.add(Map.entry(row, at)); }
            catch (IllegalArgumentException e) { System.err.println("Linea del catalogo no valida (" + row.get("CODIGO") + "): " + e.getMessage()); }
        }
        due.sort(Comparator.<Map.Entry<Map<String, String>, Instant>, Instant>comparing(Map.Entry::getValue)
            .thenComparingInt(e -> Models.PRIORITIES.indexOf(e.getKey().get("PRIORIDAD")))
            .thenComparing(e -> e.getKey().get("CODIGO")));
        var results = new ArrayList<Map<String, String>>();
        for (var entry : due) results.add(execute(entry.getKey(), "EJECUTOR", entry.getValue().toString()));
        return results;
    }

    /** Ejecucion inmediata de una estrategia del catalogo (por ejemplo, volver a correr una que fallo). */
    public Map<String, String> now(String code) throws Exception {
        var row = FlatCatalog.read(runtime).rows().stream().filter(r -> code.equalsIgnoreCase(r.get("CODIGO"))).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No existe la estrategia " + code + " en el catalogo."));
        Models.require("SI".equals(row.get("APROBADA")), "La estrategia " + code + " no tiene el script aprobado.");
        return execute(row, "EJECUTOR_MANUAL", "");
    }

    /** El CUANDO de la linea, con las mismas reglas que usa la aplicacion (Schedules). */
    static Strategy schedule(Map<String, String> row) {
        var list = (java.util.function.Function<String, List<String>>) v -> v == null || v.isBlank() ? List.of() : List.of(v.split(","));
        String interval = row.get("INTERVALO_H");
        return new Strategy(row.get("ID"), row.get("CODIGO"), null, "catalogo", null, row.get("PRIORIDAD"), true, "DATABASE", null, null,
            false, false, false, "FULL", false, false, row.get("INICIO"), row.get("FRECUENCIA"), list.apply(row.get("DIAS")),
            list.apply(row.get("HORAS")), interval == null || interval.isBlank() ? null : Integer.valueOf(interval), null, null);
    }

    private Map<String, String> execute(Map<String, String> row, String source, String planned) throws Exception {
        String code = row.get("CODIGO"), id = UUID.randomUUID().toString(), started = Instant.now().toString();
        Path logs = directory().resolve("logs");
        Files.createDirectories(logs);
        Path log = logs.resolve(code + "_" + LocalDateTime.now(zone).format(STAMP) + ".log");
        String status, message, exit = "";
        List<String> pieces = List.of(), details = List.of();
        System.out.println(LocalDateTime.now(zone).withNano(0) + "  " + code + "  " + row.get("ESTRATEGIA") + " -> RMAN");
        try {
            Path scriptFile = runtime.resolve(row.get("SCRIPT")).normalize();
            Models.require(scriptFile.startsWith(runtime.resolve("scripts").normalize()) && Files.exists(scriptFile), "No se encontro el script " + row.get("SCRIPT") + ".");
            String script = Files.readString(scriptFile, StandardCharsets.UTF_8);
            // Solo se ejecuta exactamente lo que el administrador aprobo.
            Models.require(RmanScript.hash(script).equals(row.get("HUELLA")), "El script " + scriptFile.getFileName() + " cambio despues de aprobarse; no se ejecuto.");
            var result = runner.run(row.get("CONTENEDOR"), code, script, log);
            exit = String.valueOf(result.code());
            pieces = Rman.pieces(result.output());
            var errors = Rman.errors(result.output());
            if (Rman.successful(result)) {
                details = Rman.warnings(result.output());
                status = details.isEmpty() ? "EXITOSO" : "CON_ADVERTENCIAS";
                message = (details.isEmpty() ? "Respaldo realizado por el ejecutor. " : "Respaldo realizado con advertencias. ") + pieces.size() + " pieza(s) informadas por RMAN.";
                if (pieces.isEmpty()) { status = "CON_ADVERTENCIAS"; details = List.of("RMAN no informo piezas de respaldo en su salida."); }
            } else {
                details = errors;
                status = result.timedOut() ? "INCIERTO" : "FALLIDO";
                message = result.timedOut() ? "Tiempo de espera agotado; comprueba RMAN en el servidor." : "RMAN informo un error: "
                    + errors.stream().filter(l -> !l.startsWith("RMAN-03002") && !l.startsWith("RMAN-03009")).findFirst().orElse(errors.isEmpty() ? "codigo de salida " + result.code() : errors.get(0));
            }
        } catch (Exception e) {
            status = "FALLIDO";
            message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (!Files.exists(log)) Files.writeString(log, "El ejecutor no invoco RMAN: " + message + "\n");
        }
        String finished = Instant.now().toString(), logPath = runtime.relativize(log.toAbsolutePath().normalize()).toString().replace('\\', '/');
        var result = new LinkedHashMap<String, String>();
        result.put("ID", id); result.put("CODIGO", code); result.put("ESTRATEGIA_ID", row.get("ID")); result.put("ORIGEN", source);
        result.put("PROGRAMADA", planned); result.put("INICIO", started); result.put("FIN", finished); result.put("RESULTADO", status);
        result.put("CODIGO_SALIDA", exit); result.put("HUELLA", row.get("HUELLA")); result.put("PIEZAS", String.join(";", pieces));
        result.put("LOG", logPath); result.put("MENSAJE", message); result.put("DETALLES", String.join(" ;; ", details));
        appendResult(result);
        FlatCatalog.updateResult(runtime, code, Map.of("ULTIMA_EJECUCION", finished, "RESULTADO", status, "PIEZAS", String.join(";", pieces), "LOG", logPath));
        System.out.println(LocalDateTime.now(zone).withNano(0) + "  " + code + "  " + status + "  " + message + "  log: " + logPath);
        return result;
    }

    private void appendResult(Map<String, String> result) throws Exception {
        Files.createDirectories(directory());
        Path file = directory().resolve(RESULTS);
        var line = new StringBuilder();
        if (!Files.exists(file)) line.append(String.join("|", RESULT_COLUMNS)).append('\n');
        line.append(String.join("|", RESULT_COLUMNS.stream().map(c -> FlatCatalog.clean(result.get(c))).toList())).append('\n');
        Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Resultados registrados por el ejecutor, para que la aplicacion los incorpore a su historial. */
    public static List<Map<String, String>> results(Path runtime) throws Exception {
        Path file = runtime.resolve("ejecutor").resolve(RESULTS);
        if (!Files.exists(file)) return List.of();
        var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        var rows = new ArrayList<Map<String, String>>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            var cells = lines.get(i).split("\\|", -1);
            var row = new LinkedHashMap<String, String>();
            for (int c = 0; c < RESULT_COLUMNS.size(); c++) row.put(RESULT_COLUMNS.get(c), c < cells.length ? cells[c] : "");
            rows.add(row);
        }
        return rows;
    }

    /**
     * RMAN dentro del contenedor, como lo haria system("rman target / cmdfile=RMA0001.rma log=..."):
     * copia el script al servidor, pide el LOG a RMAN con LOG= y lo trae de vuelta.
     */
    public static Rman.Result docker(String container, String code, String script, Path log) throws Exception {
        Models.require(container != null && container.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}"), "Contenedor no valido.");
        Models.require(code != null && code.matches("RMA[0-9]{4,6}"), "Codigo de script no valido.");
        String dir = "/tmp/gestor-rman", remoteScript = dir + "/" + code + Rman.SCRIPT_EXT, remoteLog = dir + "/" + log.getFileName();
        Path side = log.resolveSibling(log.getFileName() + ".docker");
        var copy = Rman.command(List.of("docker", "exec", "-i", "-u", "oracle", container, "sh", "-c", "mkdir -p " + dir + " && cat > " + remoteScript),
            script, side, Duration.ofSeconds(30));
        if (copy.code() != 0) return new Rman.Result(copy.code(), "No se pudo copiar el script al contenedor: " + copy.output(), copy.timedOut());
        var run = Rman.command(List.of("docker", "exec", "-u", "oracle", "-e", "NLS_LANG=AMERICAN_AMERICA.AL32UTF8", container,
            "rman", "target", "/", "cmdfile=" + remoteScript, "log=" + remoteLog), "", side, Duration.ofHours(4));
        var fetched = Rman.command(List.of("docker", "exec", "-u", "oracle", container, "cat", remoteLog), "", log, Duration.ofSeconds(60));
        String output = fetched.code() == 0 ? fetched.output() : run.output();
        if (fetched.code() != 0) Files.writeString(log, output);
        Files.deleteIfExists(side);
        return new Rman.Result(run.code(), output, run.timedOut());
    }

    public static void main(String[] args) throws Exception {
        var options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) options.put(args[i], args[++i]);
            else options.put(args[i], "");
        }
        Path runtime = Path.of(options.getOrDefault("--datos", System.getProperty("app.data", "runtime"))).toAbsolutePath();
        var ejecutor = new Ejecutor(runtime, Ejecutor::docker, BackupService.ZONE_ID);
        if (options.containsKey("--ahora")) {
            var result = ejecutor.now(options.get("--ahora").toUpperCase());
            System.exit(List.of("EXITOSO", "CON_ADVERTENCIAS").contains(result.get("RESULTADO")) ? 0 : 1);
        }
        Models.require(Files.exists(runtime.resolve(FlatCatalog.FILE)), "No existe " + runtime.resolve(FlatCatalog.FILE) + ". Abre la aplicacion una vez para generarlo.");
        if (!"EXTERNO".equals(FlatCatalog.read(runtime).scheduler()) && !options.containsKey("--forzar"))
            throw new IllegalStateException("El catalogo indica Planificador: INTERNO (la aplicacion ya ejecuta los horarios). "
                + "Inicia la aplicacion con -Dapp.planificador=externo para que este ejecutor sea el unico que respalda, o usa --forzar.");
        Files.createDirectories(ejecutor.directory());
        var lockChannel = java.nio.channels.FileChannel.open(ejecutor.directory().resolve("ejecutor.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        if (lockChannel.tryLock() == null) throw new IllegalStateException("Ya hay un ejecutor en marcha sobre este catalogo.");
        System.out.println("Ejecutor RMAN: lee " + runtime.resolve(FlatCatalog.FILE) + " cada 20 s. Ctrl+C para detenerlo.");
        Instant last = Instant.now();
        while (true) {
            Instant now = Instant.now();
            try { ejecutor.tick(last, now); }
            catch (Exception e) { System.err.println("Error del ejecutor: " + e.getMessage()); }
            last = now;
            Thread.sleep(20_000);
        }
    }
}
