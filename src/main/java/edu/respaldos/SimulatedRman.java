package edu.respaldos;

import edu.respaldos.Models.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Sustituto de Rman para la demostracion publica: no usa Docker ni Oracle. Produce salidas con el
 * formato de RMAN para que el resto de la aplicacion (evidencia, estados, alertas) funcione igual.
 * Toda salida empieza con la marca [SIMULACION].
 */
public class SimulatedRman extends Rman {
    public static final String MARK = "[SIMULACION] Salida generada sin Oracle ni RMAN para demostrar la herramienta.";
    private static final Pattern FORMAT = Pattern.compile("FORMAT '([^']+)/%d");
    private static final Pattern TAG = Pattern.compile("TAG '([^']+)'");
    private static final Set<String> EXISTING = Set.of(Models.DEFAULT_DESTINATION);
    private final Map<String, Long> sizes = new ConcurrentHashMap<>();
    private final AtomicInteger piece = new AtomicInteger(100);
    private final long delayMillis;

    public SimulatedRman(long delayMillis) { this.delayMillis = delayMillis; }

    @Override
    public Result execute(Database db, String script, Path log, Duration timeout) throws Exception {
        if (delayMillis > 0) Thread.sleep(delayMillis);
        var out = new StringBuilder(MARK).append("\n\nRecovery Manager: Release 23.26.1.0.0 - Production (simulado)\n\nconnected to target database: FREE (DBID=1516667409)\n\n");
        int code = output(db, script, Instant.now(), out, null);
        Files.createDirectories(log.toAbsolutePath().getParent());
        Files.writeString(log, out, StandardCharsets.UTF_8);
        return new Result(code, out.toString(), false);
    }

    /** Genera la salida simulada; {@code failWith} fuerza un error de escritura para el historial de ejemplo. */
    int output(Database db, String script, Instant at, StringBuilder out, String failWith) {
        var m = FORMAT.matcher(script);
        String destination = m.find() ? m.group(1) : Models.DEFAULT_DESTINATION;
        String day = DateTimeFormatter.ofPattern("yyyyMMdd").format(at.atZone(ZoneOffset.UTC));
        String when = DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH).format(at.atZone(ZoneOffset.UTC)).toUpperCase();
        boolean noArchivelog = db.container().contains("noarch");
        for (String raw : script.split("\n")) {
            String t = raw.trim();
            out.append("RMAN> ").append(raw).append('\n');
            if (t.startsWith("BACKUP")) {
                boolean data = t.contains(" DATABASE") || t.contains(" TABLESPACE ") || t.contains(" DATAFILE ");
                if (noArchivelog && data) return error(out, when, "ORA-19602: cannot backup or copy active file in NOARCHIVELOG mode");
                if (!EXISTING.contains(destination)) return error(out, when, "ORA-19504: failed to create file \"" + destination + "/FREE_" + day + "_sim_1_1.bkp\"\nORA-27040: file create error, unable to create file");
                if (failWith != null && data) return error(out, when, failWith);
                var tag = TAG.matcher(t);
                String file = destination + "/FREE_" + day + "_sim" + piece.incrementAndGet() + "_1_1.bkp";
                sizes.put(file, size(t));
                out.append("\nStarting backup at ").append(when).append("\nchannel d1: starting ").append(t.contains("COMPRESSED") ? "compressed " : "")
                    .append(t.contains("ARCHIVELOG") ? "archived log backup set" : t.contains("INCREMENTAL") ? "incremental datafile backup set" : "full datafile backup set")
                    .append("\nchannel d1: starting piece 1 at ").append(when)
                    .append("\npiece handle=").append(file).append(" tag=").append(tag.find() ? tag.group(1) : "TAG" + day).append(" comment=NONE")
                    .append("\nchannel d1: backup set complete, elapsed time: 00:00:0").append(1 + Math.abs(file.hashCode()) % 8)
                    .append("\nFinished backup at ").append(when).append("\n\n");
            } else if (t.startsWith("CROSSCHECK")) {
                out.append("using channel ORA_DISK_1\ncrosschecked backup piece: found to be 'AVAILABLE'\nCrosschecked 3 objects\n\n");
            } else if (t.startsWith("RESTORE") && t.contains("VALIDATE")) {
                out.append("\nStarting restore at ").append(when).append("\nchannel ORA_DISK_1: starting validation of datafile backup set\nchannel ORA_DISK_1: validation complete, elapsed time: 00:00:01\nFinished restore at ").append(when).append("\n\n");
            }
        }
        out.append("\nRecovery Manager complete.\n");
        return 0;
    }

    private static int error(StringBuilder out, String when, String message) {
        out.append("\nRMAN-00571: ===========================================================\nRMAN-00569: =============== ERROR MESSAGE STACK FOLLOWS ===============\nRMAN-00571: ===========================================================\n")
            .append("RMAN-03009: failure of backup command on d1 channel at ").append(when).append('\n').append(message).append("\n\nRecovery Manager complete.\n");
        return 1;
    }

    private static long size(String command) {
        long base = command.contains(" DATABASE") ? 2_900_000_000L : command.contains("ARCHIVELOG") ? 48_000_000L : command.contains("CONTROLFILE") ? 18_000_000L
            : command.contains("SPFILE") ? 114_688L : command.contains("INCREMENTAL LEVEL 1") ? 6_000_000L : 34_000_000L;
        return command.contains("COMPRESSED") ? base / 3 : base;
    }

    long sizeOf(String file) { return sizes.getOrDefault(file, 1_048_576L); }

    @Override
    public FileCheck checkFiles(Database db, List<String> files, Path log) {
        var existing = new LinkedHashMap<String, Long>();
        for (String f : files) existing.put(f, sizeOf(f));
        return new FileCheck(existing, List.of(), true);
    }

    @Override
    public DatabaseStatus diagnose(Database db, Path directory, Collection<String> destinations) {
        boolean noArchivelog = db.container().contains("noarch");
        var files = List.of(
            new Datafile(1, "CDB$ROOT", "SYSTEM", 1_101_004_800L, "/opt/oracle/oradata/FREE/system01.dbf"),
            new Datafile(3, "CDB$ROOT", "SYSAUX", 671_088_640L, "/opt/oracle/oradata/FREE/sysaux01.dbf"),
            new Datafile(4, "CDB$ROOT", "UNDOTBS1", 419_430_400L, "/opt/oracle/oradata/FREE/undotbs01.dbf"),
            new Datafile(7, "CDB$ROOT", "USERS", 7_340_032L, "/opt/oracle/oradata/FREE/users01.dbf"),
            new Datafile(12, "FREEPDB1", "SYSTEM", 304_087_040L, "/opt/oracle/oradata/FREE/FREEPDB1/system01.dbf"),
            new Datafile(13, "FREEPDB1", "SYSAUX", 450_887_680L, "/opt/oracle/oradata/FREE/FREEPDB1/sysaux01.dbf"),
            new Datafile(14, "FREEPDB1", "UNDOTBS1", 104_857_600L, "/opt/oracle/oradata/FREE/FREEPDB1/undotbs01.dbf"),
            new Datafile(15, "FREEPDB1", "USERS", 7_340_032L, "/opt/oracle/oradata/FREE/FREEPDB1/users01.dbf"),
            new Datafile(16, "FREEPDB1", "LAB_DATOS", 33_554_432L, "/opt/oracle/oradata/FREE/FREEPDB1/lab_datos01.dbf"));
        var free = new LinkedHashMap<String, Long>();
        for (String d : new TreeSet<>(destinations)) free.put(d, EXISTING.contains(d) ? 21_800_000L : -1L);
        return new DatabaseStatus(db.id(), Instant.now().toString(), true, true, "23.26.1.0.0 (simulada)", "FREE", "READ WRITE",
            noArchivelog ? "NOARCHIVELOG" : "ARCHIVELOG", List.of("PDB$SEED (READ ONLY)", "FREEPDB1 (READ WRITE)"), files, noArchivelog ? 0 : 12, free,
            "[Simulacion] Base en " + (noArchivelog ? "NOARCHIVELOG" : "ARCHIVELOG") + ".");
    }
}
