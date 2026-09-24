package edu.respaldos;

import edu.respaldos.Models.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Datos de ejemplo del modo simulacion: dos bases, cinco estrategias y una semana de historial con
 * exitos, fallas, ventanas excedidas y dos respaldos que no se ejecutaron.
 */
final class Demo {
    private Demo() {}
    private static final String WHO = "DBA del grupo (simulado)";

    static void seed(Catalog catalog, SimulatedRman rman, Path runtime) throws Exception {
        if (!catalog.databases().isEmpty()) return;
        var lab = new Database(null, "Laboratorio (simulado)", "sim-archivelog");
        var noarch = new Database(null, "Contabilidad en NOARCHIVELOG (simulada)", "sim-noarchivelog");
        String dest = Models.DEFAULT_DESTINATION;
        String start = LocalDate.now(BackupService.ZONE_ID).minusDays(7).toString();
        for (var db : List.of(lab, noarch)) {
            catalog.save(db);
            catalog.save(rman.diagnose(db, runtime, List.of(dest)));
        }
        var strategies = List.of(
            new Strategy(null, "EST001 - Datos del laboratorio", "Pedidos del laboratorio; su perdida detiene la facturacion.", lab.id(), WHO, "ALTA", true,
                "TABLESPACE", List.of("FREEPDB1:LAB_DATOS"), List.of(), true, true, true, "FULL", true, true, start, "DIARIA", null, List.of("13:00", "15:00", "18:00", "21:00"), null, 30, dest),
            new Strategy(null, "EST002 - Base completa nivel 0", "Base de la cadena incremental y respaldo integral semanal.", lab.id(), WHO, "BAJA", true,
                "DATABASE", List.of(), List.of(), true, true, true, "LEVEL0", true, true, start, "SEMANAL", List.of("SUN"), List.of("02:00"), null, 120, dest),
            new Strategy(null, "EST003 - Usuarios incremental", "Tablespace USERS; la informacion se puede reconstruir.", lab.id(), WHO, "MEDIA", true,
                "TABLESPACE", List.of("FREEPDB1:USERS"), List.of(), false, false, false, "CUMULATIVE", true, false, start, "DIARIA", null, List.of("02:00", "18:00"), null, 20, dest),
            new Strategy(null, "EST004 - Archived logs cada 4 h", "Redo archivado para recuperar hasta un punto en el tiempo.", lab.id(), WHO, "ALTA", true,
                "COMPONENTS", List.of(), List.of(), true, false, false, "FULL", true, false, start, "INTERVALO", Models.DAYS, List.of("00:30"), 4, 15, dest),
            // Queda sin aprobar y con configuracion incompleta a proposito: muestra las advertencias.
            new Strategy(null, "EST010 - Contabilidad nocturna", null, noarch.id(), null, "ALTA", true,
                "DATABASE", List.of(), List.of(), false, true, true, "FULL", true, false, start, "DIARIA", null, List.of("23:00"), null, 60, dest));

        Instant now = Instant.now(), approved = now.minus(Duration.ofDays(7));
        int n = 0;
        for (var s : strategies) {
            catalog.save(s);
            if (!s.databaseId().equals(lab.id())) continue;
            String script = RmanScript.backup(s, lab);
            catalog.save(new Approval(s.id(), RmanScript.hash(script), approved.toString(), WHO));
            for (var t : Schedules.occurrences(s, approved, now.minus(Duration.ofMinutes(2)), BackupService.ZONE_ID, 200)) {
                n++;
                if (n == 6 || n == 61) continue; // la aplicacion "estaba apagada": el control preventivo lo detecta
                history(catalog, rman, runtime, lab, s, script, t, n);
            }
        }
    }

    private static void history(Catalog catalog, SimulatedRman rman, Path runtime, Database db, Strategy s, String script, Instant at, int n) throws Exception {
        var out = new StringBuilder(SimulatedRman.MARK).append("\n\nRecovery Manager: Release 23.26.1.0.0 - Production (simulado)\n\n");
        String failure = n == 9 || n == 40 ? "ORA-19502: write error on file \"/opt/oracle/backup/FREE_sim.bkp\", block number 1024 (block size=8192)\nORA-27072: File I/O error" : null;
        int code = rman.output(db, script, at, out, failure);
        long seconds = 25 + (n * 37L) % 180;
        var details = new ArrayList<String>();
        var pieces = new ArrayList<String>();
        String status, message;
        if (code != 0) {
            details.addAll(Rman.errors(out.toString()));
            status = "FALLIDO";
            message = "RMAN informo un error: " + details.stream().filter(l -> l.startsWith("ORA-")).findFirst().orElse(details.get(0));
        } else {
            for (String p : Rman.pieces(out.toString())) pieces.add(p + " (" + RmanScript.human(rman.sizeOf(p)) + ")");
            if (n % 19 == 7 && s.windowMinutes() != null) {
                seconds = s.windowMinutes() * 60L + 240;
                details.add("La ejecucion duro " + seconds / 60 + " min y excedio la ventana de " + s.windowMinutes() + " min.");
            }
            status = details.isEmpty() ? "EXITOSO" : "CON_ADVERTENCIAS";
            if (s.verifyAfter()) details.add("Verificacion posterior correcta: CROSSCHECK y RESTORE ... VALIDATE sin errores.");
            message = (status.equals("EXITOSO") ? "Respaldo realizado. " : "Respaldo realizado con advertencias. ") + pieces.size() + " pieza(s) comprobadas en " + s.destination() + ".";
        }
        String id = UUID.randomUUID().toString();
        var e = new Execution(id, s.id(), s.name(), db.id(), db.name(), "BACKUP", RmanScript.how(s) + " | " + RmanScript.what(s), "HORARIO",
            at.toString(), at.toString(), at.plusSeconds(seconds).toString(), status, code, message, s.destination(), pieces, details, RmanScript.hash(script));
        Path dir = runtime.resolve("executions").resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("script.rman"), script);
        Files.writeString(dir.resolve("output.log"), out);
        catalog.insert(e, s.id() + "|" + at);
    }
}
