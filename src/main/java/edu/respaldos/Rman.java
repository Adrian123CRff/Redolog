package edu.respaldos;

import edu.respaldos.Models.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Ejecuta RMAN y SQL*Plus dentro del contenedor Oracle e interpreta su salida. */
public class Rman {
    private static final Pattern ERRORS = Pattern.compile("(?m)^\\s*(?:ORA-[0-9]{5}|RMAN-00569)");
    private static final Pattern ERROR_LINES = Pattern.compile("(?m)^\\s*((?:RMAN|ORA)-\\d{5}:.*)$");
    private static final Pattern WARNING_LINES = Pattern.compile("(?m)^\\s*(RMAN-\\d{5}: WARNING.*)$");
    private static final Pattern PIECES = Pattern.compile("(?:piece handle|output file name)=(\\S+)");
    private static final Pattern NO_PARENT = Pattern.compile("(?m)^.*no parent backup or copy of datafile.*$");

    public record Result(int code, String output, boolean timedOut) {}
    public record FileCheck(Map<String, Long> existing, List<String> missing, boolean checked) {}

    public static Result command(List<String> args, String input, Path log, Duration timeout) throws Exception {
        Files.createDirectories(log.toAbsolutePath().getParent());
        var process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try (var stream = process.getOutputStream()) { stream.write(input.getBytes(StandardCharsets.UTF_8)); }
        boolean finished;
        try { finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { process.destroy(); Thread.currentThread().interrupt(); throw e; }
        if (!finished) { process.destroy(); process.waitFor(3, TimeUnit.SECONDS); if (process.isAlive()) process.destroyForcibly(); }
        return new Result(finished ? process.exitValue() : -1, readTail(log, 250_000), !finished);
    }

    public static String readTail(Path path, int max) throws Exception {
        if (!Files.exists(path)) return "";
        try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size(); channel.position(Math.max(0, size - max));
            var buffer = java.nio.ByteBuffer.allocate((int) Math.min(size, max));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {}
            return (size > max ? "[Fragmento final del registro]\n" : "") + new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        }
    }

    public static boolean successful(Result r) { return !r.timedOut() && r.code() == 0 && !ERRORS.matcher(r.output()).find(); }

    /** Lineas de error RMAN/ORA sin los separadores del bloque de errores. */
    public static List<String> errors(String output) {
        var list = new ArrayList<String>();
        var m = ERROR_LINES.matcher(output);
        while (m.find()) {
            String line = m.group(1).trim();
            if (!line.startsWith("RMAN-00571") && !line.startsWith("RMAN-00569") && !list.contains(line)) list.add(line);
        }
        return list;
    }

    /** Advertencias que no detienen RMAN pero que el administrador debe conocer. */
    public static List<String> warnings(String output) {
        var list = new ArrayList<String>();
        var m = WARNING_LINES.matcher(output);
        while (m.find()) if (!list.contains(m.group(1).trim())) list.add(m.group(1).trim());
        if (NO_PARENT.matcher(output).find())
            list.add("RMAN no encontro un respaldo base (nivel 0) para algun datafile.");
        return list;
    }

    public static List<String> pieces(String output) {
        var list = new ArrayList<String>();
        var m = PIECES.matcher(output);
        while (m.find()) if (!list.contains(m.group(1))) list.add(m.group(1));
        return list;
    }

    public Result execute(Database db, String script, Path log, Duration timeout) throws Exception {
        return command(List.of("docker", "exec", "-i", "-u", "oracle", "-e", "NLS_LANG=AMERICAN_AMERICA.AL32UTF8", db.container(), "rman", "target", "/"), script, log, timeout);
    }

    /** Comprueba en el servidor Oracle que cada pieza reportada por RMAN existe, y obtiene su tamano. */
    public FileCheck checkFiles(Database db, List<String> files, Path log) throws Exception {
        if (files.isEmpty()) return new FileCheck(Map.of(), List.of(), true);
        var args = new ArrayList<>(List.of("docker", "exec", "-u", "oracle", db.container(), "stat", "-c", "%s|%n", "--"));
        args.addAll(files);
        var result = command(args, "", log, Duration.ofSeconds(20));
        if (result.timedOut() || result.output().contains("Error response from daemon") || result.output().contains("failed to connect"))
            return new FileCheck(Map.of(), List.of(), false);
        var existing = new LinkedHashMap<String, Long>();
        for (String line : result.output().split("\\R")) {
            int bar = line.indexOf('|');
            if (bar > 0 && line.substring(0, bar).matches("\\d+")) existing.put(line.substring(bar + 1).trim(), Long.parseLong(line.substring(0, bar)));
        }
        return new FileCheck(existing, files.stream().filter(f -> !existing.containsKey(f)).toList(), true);
    }

    public DatabaseStatus diagnose(Database db, Path directory, Collection<String> destinations) throws Exception {
        String now = java.time.Instant.now().toString();
        var binary = command(List.of("docker", "exec", "-u", "oracle", db.container(), "sh", "-c", "test -x \"$ORACLE_HOME/bin/rman\""), "", directory.resolve("rman-check.log"), Duration.ofSeconds(15));
        var sql = command(List.of("docker", "exec", "-i", "-u", "oracle", db.container(), "sqlplus", "-s", "/", "as", "sysdba"), """
            whenever sqlerror exit failure
            set pages 0 feedback off heading off lines 700 trimspool on
            select 'VERSION|'||version_full from v$instance;
            select 'DATABASE|'||name||'|'||open_mode||'|'||log_mode from v$database;
            select 'PDB|'||name||'|'||open_mode from v$pdbs order by con_id;
            select 'DF|'||d.file#||'|'||nvl(c.name,'-')||'|'||t.name||'|'||d.bytes||'|'||d.name
              from v$datafile d join v$tablespace t on t.ts#=d.ts# and t.con_id=d.con_id
              left join v$containers c on c.con_id=d.con_id order by d.file#;
            select 'ARCH|'||count(*) from v$archived_log where deleted='NO' and status='A';
            exit;
            """, directory.resolve("sql-check.log"), Duration.ofSeconds(30));
        if (!successful(sql)) {
            var errs = errors(sql.output());
            String reason = !errs.isEmpty() ? errs.get(0) : sql.output().lines().filter(l -> !l.isBlank()).findFirst().orElse("sin respuesta");
            return new DatabaseStatus(db.id(), now, false, binary.code() == 0, null, null, null, null, List.of(), List.of(), 0, Map.of(), reason.trim());
        }
        String version = null, name = null, openMode = null, logMode = null;
        int archived = 0;
        var pdbs = new ArrayList<String>();
        var files = new ArrayList<Datafile>();
        for (String line : sql.output().split("\\R")) {
            String[] p = line.trim().split("\\|", -1);
            switch (p[0]) {
                case "VERSION" -> version = p[1];
                case "DATABASE" -> { name = p[1]; openMode = p[2]; logMode = p[3]; }
                case "PDB" -> pdbs.add(p[1] + " (" + p[2] + ")");
                case "DF" -> { if (p.length >= 6) files.add(new Datafile(Integer.parseInt(p[1]), p[2], p[3], parseLong(p[4]), p[5])); }
                case "ARCH" -> archived = (int) parseLong(p[1]);
                default -> {}
            }
        }
        var free = new LinkedHashMap<String, Long>();
        for (String dest : new TreeSet<>(destinations)) free.put(dest, freeKb(db, dest, directory));
        String message = "ARCHIVELOG".equals(logMode) ? "Conexion correcta; base en ARCHIVELOG." : "Conexion correcta; base en " + logMode + ".";
        if (binary.code() != 0) message += " RMAN no esta disponible en el contenedor.";
        return new DatabaseStatus(db.id(), now, true, binary.code() == 0, version, name, openMode, logMode, pdbs, files, archived, free, message);
    }

    /** Espacio libre en KB; -1 si la ruta no existe en el servidor. */
    private long freeKb(Database db, String destination, Path directory) throws Exception {
        var df = command(List.of("docker", "exec", "-u", "oracle", db.container(), "df", "-Pk", destination), "", directory.resolve("df.log"), Duration.ofSeconds(15));
        if (df.code() != 0) return -1;
        var lines = df.output().strip().split("\\R");
        String[] cols = lines[lines.length - 1].trim().split("\\s+");
        return cols.length >= 4 ? parseLong(cols[3]) : -1;
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value.trim()); } catch (Exception e) { return 0; }
    }
}
