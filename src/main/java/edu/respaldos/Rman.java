package edu.respaldos;

import edu.respaldos.Models.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class Rman {
    private static final Pattern ERRORS = Pattern.compile("(?m)^\\s*(?:ORA-[0-9]{5}|RMAN-00569)");
    public record Result(int code, String output, boolean timedOut) {}
    public static String script(Strategy s, String operation) {
        Models.require(List.of("BACKUP", "VALIDATE").contains(operation), "Operacion no valida.");
        String target = s.scope().equals("DATABASE") ? "DATABASE" : "TABLESPACE " + String.join(", ", s.tablespaces());
        if (operation.equals("VALIDATE")) return "RESTORE " + target + " VALIDATE;\nEXIT;\n";
        String method = switch(s.method()) {
            case "LEVEL0" -> "INCREMENTAL LEVEL 0 ";
            case "LEVEL1" -> "INCREMENTAL LEVEL 1 ";
            case "CUMULATIVE" -> "INCREMENTAL LEVEL 1 CUMULATIVE ";
            default -> "";
        };
        StringBuilder script = new StringBuilder("RUN {\n  ALLOCATE CHANNEL disco DEVICE TYPE DISK FORMAT '/opt/oracle/backup/%d_%T_%U.bkp';\n");
        if (s.archivelogs()) script.append("  SQL 'ALTER SYSTEM ARCHIVE LOG CURRENT';\n  BACKUP ARCHIVELOG ALL;\n");
        script.append("  BACKUP ").append(method).append(target).append(";\n");
        if (s.archivelogs()) script.append("  SQL 'ALTER SYSTEM ARCHIVE LOG CURRENT';\n  BACKUP ARCHIVELOG ALL;\n");
        if (s.controlfile()) script.append("  BACKUP CURRENT CONTROLFILE;\n  BACKUP SPFILE;\n");
        return script.append("  RELEASE CHANNEL disco;\n}\nEXIT;\n").toString();
    }
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
            long size = channel.size(); channel.position(Math.max(0, size-max));
            var buffer = java.nio.ByteBuffer.allocate((int)Math.min(size,max));
            while(buffer.hasRemaining() && channel.read(buffer) > 0) {}
            return (size > max ? "[Fragmento final del registro]\n" : "") + new String(buffer.array(),0,buffer.position(),StandardCharsets.UTF_8);
        }
    }
    public static boolean successful(Result r) { return !r.timedOut() && r.code() == 0 && !ERRORS.matcher(r.output()).find(); }
    public Result execute(Database db, String script, Path log) throws Exception {
        return command(List.of("docker","exec","-i","-u","oracle","-e","NLS_LANG=AMERICAN_AMERICA.AL32UTF8",db.container(),"rman","target","/"), script, log, Duration.ofHours(1));
    }
    public Map<String,Object> diagnose(Database db, Path directory) throws Exception {
        var binary = command(List.of("docker","exec","-u","oracle",db.container(),"sh","-c","test -x \"$ORACLE_HOME/bin/rman\""),"",directory.resolve("rman-check.log"),Duration.ofSeconds(15));
        var sql = command(List.of("docker","exec","-i","-u","oracle",db.container(),"sqlplus","-s","/","as","sysdba"),
            "whenever sqlerror exit failure\nset pages 0 feedback off heading off lines 220\nselect 'VERSION|'||version_full from v$instance;\nselect 'DATABASE|'||name||'|'||open_mode||'|'||log_mode from v$database;\nselect 'PDB|'||name||'|'||open_mode from v$pdbs;\nexit;\n",directory.resolve("sql-check.log"),Duration.ofSeconds(20));
        boolean ready = successful(sql) && sql.output().contains("READ WRITE|ARCHIVELOG") && binary.code() == 0;
        return Map.of("ready",ready,"rmanAvailable",binary.code()==0,"checkedAt",java.time.Instant.now().toString(),"output",sql.output(),"message",ready ? "Conexion y RMAN disponibles; ARCHIVELOG activo." : "Revisa el estado de Oracle, ARCHIVELOG y la disponibilidad de RMAN.");
    }
}
