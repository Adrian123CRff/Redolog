package edu.respaldos;

import edu.respaldos.Models.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

/**
 * Transforma una estrategia (QUÉ + CÓMO + CUÁNDO + DESTINO) en instrucciones RMAN y la valida
 * contra el estado conocido de la base. No ejecuta nada: es una funcion pura para poder probarla.
 */
public final class RmanScript {
    private RmanScript() {}

    public static String backup(Strategy s, Database db) {
        String options = (s.compressed() ? "AS COMPRESSED BACKUPSET " : "") + "TAG '" + s.tag() + "' ";
        var script = new StringBuilder();
        comment(script, "Estrategia: " + s.name());
        comment(script, "Base: " + db.name() + " (" + db.container() + ") | Prioridad: " + s.priority());
        comment(script, "Que: " + what(s) + " | Como: " + how(s));
        comment(script, "Destino: " + s.destination() + " (DISK)");
        comment(script, "Politica v2: " + s.startDate() + " | " + s.frequency() + " | " + s.days() + " | " + s.times()
            + " | intervalo=" + s.intervalHours() + " | ventana=" + s.windowMinutes() + " | activa=" + s.enabled());
        comment(script, "Responsable: " + s.responsible() + " | Descripcion: " + s.description());
        if (!s.tablespacePriorities().isEmpty())
            comment(script, "Prioridad por tablespace: " + String.join(", ", s.tablespaces().stream().map(t -> t + "=" + s.priorityOf(t)).toList()));
        script.append("RUN {\n");
        script.append("  ALLOCATE CHANNEL d1 DEVICE TYPE DISK FORMAT '").append(s.destination()).append("/%d_%T_%U.bkp';\n");
        if (s.backsUpData()) for (String target : targets(s)) script.append("  BACKUP ").append(level(s)).append(options).append(target).append(";\n");
        // RMAN no copia los redo en linea: se archiva el grupo actual para que su contenido quede en un archived log respaldado.
        if (s.onlineRedo()) script.append("  SQL 'ALTER SYSTEM ARCHIVE LOG CURRENT';\n");
        // Despues de los datos: RMAN archiva el redo en linea actual y respalda solo los logs nuevos,
        // incluido el redo generado durante el respaldo, necesario para recuperar hasta un punto consistente.
        if (s.archivelogs()) script.append("  BACKUP ").append(options).append("ARCHIVELOG ALL NOT BACKED UP 1 TIMES;\n");
        // El control file va al final para que registre todas las piezas anteriores.
        if (s.controlfile()) script.append("  BACKUP ").append(options).append("CURRENT CONTROLFILE;\n");
        if (s.spfile()) script.append("  BACKUP ").append(options).append("SPFILE;\n");
        script.append("  RELEASE CHANNEL d1;\n}\nEXIT;\n");
        return script.toString();
    }

    /** Verificacion con mecanismos de RMAN: disponibilidad (CROSSCHECK) y validez (RESTORE ... VALIDATE). */
    public static String validate(Strategy s) {
        var script = new StringBuilder();
        comment(script, "Verificacion de la estrategia: " + s.name());
        comment(script, "RESTORE ... VALIDATE lee las piezas sin restaurar nada.");
        script.append("CROSSCHECK BACKUP TAG '").append(s.tag()).append("';\n");
        if (s.backsUpData()) script.append("RESTORE ").append(target(s)).append(" VALIDATE;\n");
        if (s.archivelogs()) script.append("RESTORE ARCHIVELOG ALL VALIDATE;\n");
        if (s.controlfile()) script.append("RESTORE CONTROLFILE VALIDATE;\n");
        if (s.spfile()) script.append("RESTORE SPFILE VALIDATE;\n");
        return script.append("EXIT;\n").toString();
    }

    /** Solo esta variante se usa para certificar una ejecucion concreta. */
    public static String validateSets(Collection<Long> keys) {
        Models.require(keys != null && !keys.isEmpty(), "No hay conjuntos identificados para verificar.");
        var script = new StringBuilder("# Verificacion de conjuntos de una ejecucion concreta\n");
        for (Long key : new TreeSet<>(keys)) {
            Models.require(key != null && key > 0, "Clave de conjunto RMAN no valida.");
            script.append("VALIDATE BACKUPSET ").append(key).append(";\n");
        }
        return script.append("EXIT;\n").toString();
    }

    public static String coverageHash(Strategy s, Database db) {
        return hash(db.id() + "|" + db.container() + "|" + s.scope() + "|" + new TreeSet<>(s.tablespaces()) + "|"
            + s.datafiles() + "|" + s.method() + "|" + s.archivelogs() + "|" + s.controlfile() + "|" + s.spfile()
            + "|" + s.destination());
    }

    /**
     * Un BACKUP por nivel de prioridad, de ALTA a BAJA, para que lo mas critico se copie primero.
     * Sin prioridades propias queda un solo BACKUP, igual que antes (la huella aprobada no cambia).
     */
    static List<String> targets(Strategy s) {
        if (!s.scope().equals("TABLESPACE") || s.tablespacePriorities().isEmpty()) return List.of(target(s));
        var result = new ArrayList<String>();
        for (String level : Models.PRIORITIES) {
            var group = s.tablespaces().stream().filter(t -> s.priorityOf(t).equals(level)).toList();
            if (!group.isEmpty()) result.add("TABLESPACE " + String.join(", ", group));
        }
        return result;
    }

    static String target(Strategy s) {
        return switch (s.scope()) {
            case "DATABASE" -> "DATABASE";
            case "TABLESPACE" -> "TABLESPACE " + String.join(", ", s.tablespaces());
            case "DATAFILE" -> "DATAFILE " + String.join(", ", s.datafiles().stream().map(String::valueOf).toList());
            default -> "";
        };
    }

    static String level(Strategy s) {
        return switch (s.method()) {
            case "LEVEL0" -> "INCREMENTAL LEVEL 0 ";
            case "LEVEL1" -> "INCREMENTAL LEVEL 1 ";
            case "CUMULATIVE" -> "INCREMENTAL LEVEL 1 CUMULATIVE ";
            default -> "";
        };
    }

    public static String methodLabel(String method) {
        return switch (method) {
            case "LEVEL0" -> "Incremental nivel 0";
            case "LEVEL1" -> "Incremental nivel 1 diferencial";
            case "CUMULATIVE" -> "Incremental nivel 1 acumulativo";
            default -> "Completo (FULL)";
        };
    }

    public static String what(Strategy s) {
        var parts = new ArrayList<String>();
        switch (s.scope()) {
            case "DATABASE" -> parts.add("base completa");
            case "TABLESPACE" -> parts.add("tablespaces " + String.join(", ", s.tablespaces()));
            case "DATAFILE" -> parts.add("datafiles " + String.join(", ", s.datafiles().stream().map(String::valueOf).toList()));
            default -> {}
        }
        if (s.onlineRedo()) parts.add("redo en linea (archivado)");
        if (s.archivelogs()) parts.add("archived redo logs");
        if (s.controlfile()) parts.add("control file");
        if (s.spfile()) parts.add("SPFILE");
        return String.join(" + ", parts);
    }

    public static String how(Strategy s) {
        String how = s.backsUpData() ? methodLabel(s.method()) : "Copia de componentes";
        return how + (s.compressed() ? ", comprimido" : "") + (s.verifyAfter() ? ", verificacion posterior" : "");
    }

    public static String hash(String script) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Validacion semantica. Los ERROR impiden aprobar; el resto se muestra al administrador. */
    public static List<Issue> check(Strategy s, DatabaseStatus st) {
        var issues = new ArrayList<Issue>();
        if (s.responsible() == null) issues.add(new Issue("ADVERTENCIA", "SIN_RESPONSABLE", "Configuracion incompleta: la estrategia no tiene responsable."));
        if (s.description() == null) issues.add(new Issue("ADVERTENCIA", "SIN_DESCRIPCION", "Configuracion incompleta: falta la descripcion o justificacion de la estrategia."));
        if (s.times().isEmpty()) issues.add(new Issue("ADVERTENCIA", "SIN_HORARIO", "La estrategia no tiene programacion: no se ejecutara automaticamente."));
        if (!s.enabled()) issues.add(new Issue("INFORMATIVA", "INACTIVA", "La estrategia esta inactiva: se puede aprobar, pero no se programara."));
        if (s.frequency().equals("UNA_VEZ"))
            issues.add(new Issue("INFORMATIVA", "NO_CICLICA", "La estrategia no es ciclica: se ejecuta solo el " + s.startDate() + " y no se repite."));
        else if (!s.times().isEmpty()) {
            long gap = Schedules.maxGapHours(s, java.time.Instant.now(), BackupService.ZONE_ID);
            int rpo = Models.rpoHours(s.priority());
            if (gap > rpo) issues.add(new Issue("ADVERTENCIA", "FRECUENCIA_INSUFICIENTE", "Frecuencia insuficiente para la prioridad " + s.priority().toLowerCase()
                + ": hasta " + gap + " h entre respaldos; el objetivo es de " + rpo + " h como maximo."));
        }
        if (List.of("LEVEL1", "CUMULATIVE").contains(s.method()))
            issues.add(new Issue("INFORMATIVA", "NIVEL1", "Un incremental nivel 1 parte de un nivel 0 previo del mismo alcance; un respaldo FULL no sirve como base. Si no hay nivel 0, RMAN copia todos los bloques usados desde la creacion del datafile y lo registra como nivel 1."));
        if (st == null) {
            issues.add(new Issue("ERROR", "SIN_COMPROBAR", "Comprueba la base antes de aprobar: aun no se conoce su estado."));
            return issues;
        }
        if (!st.reachable()) {
            issues.add(new Issue("ERROR", "SIN_CONEXION", "La ultima comprobacion no pudo conectar con la base: " + st.message()));
            return issues;
        }
        if (!st.rmanAvailable()) issues.add(new Issue("ERROR", "SIN_RMAN", "RMAN no esta disponible en el servidor."));
        if (!List.of("ARCHIVELOG", "NOARCHIVELOG").contains(Objects.toString(st.logMode(), "")))
            issues.add(new Issue("ERROR", "MODO_DESCONOCIDO", "No se pudo determinar el modo de archivado."));
        if ("NOARCHIVELOG".equals(st.logMode())) {
            issues.add(new Issue("ADVERTENCIA", "NOARCHIVELOG", "La base de datos se encuentra en modo NOARCHIVELOG. Las posibilidades de recuperacion son mas limitadas. Revise la estrategia de respaldo y los requerimientos de recuperacion antes de continuar."));
            if (s.archivelogs())
                issues.add(new Issue("ERROR", "ARCHIVELOG_SIN_MODO", "La estrategia incluye archived redo logs, pero la base no los genera (NOARCHIVELOG). Quite esa opcion."));
            if (s.backsUpData() && "READ WRITE".equals(st.openMode()))
                issues.add(new Issue("ERROR", "EN_LINEA_NOARCHIVELOG", "Con la base abierta en NOARCHIVELOG RMAN no puede respaldar datafiles en linea (ORA-19602). Se requiere un respaldo consistente con la base en MOUNT, decision que queda a cargo del administrador."));
        } else if ("ARCHIVELOG".equals(st.logMode())) {
            issues.add(new Issue("INFORMATIVA", "ARCHIVELOG", "La base esta en ARCHIVELOG: admite respaldos en linea y recuperacion hasta un punto en el tiempo."));
            if (s.backsUpData() && !s.archivelogs())
                issues.add(new Issue("RECOMENDACION", "INCLUIR_ARCHIVELOGS", "La base de datos se encuentra en modo ARCHIVELOG. Considere incorporar el respaldo periodico de los archived redo logs dentro de la estrategia para mejorar las posibilidades de recuperacion."));
        }
        if (s.archivelogs() && "ARCHIVELOG".equals(st.logMode()) && st.archivedLogs() == 0)
            issues.add(new Issue("ADVERTENCIA", "SIN_ARCHIVED_LOGS", "La estrategia requiere archived redo logs y la base no tiene ninguno disponible en este momento."));
        var known = new HashSet<>(st.tablespaces());
        for (String ts : s.tablespaces())
            if (!known.contains(ts)) issues.add(new Issue("ERROR", "TABLESPACE_INEXISTENTE", "El tablespace " + ts + " no existe en la base (comprobacion " + st.checkedAt() + ")." + (ts.contains(":") ? "" : " Si pertenece a un PDB, escribelo como PDB:TABLESPACE.")));
        var files = st.datafiles().stream().map(Datafile::file).toList();
        for (int f : s.datafiles())
            if (!files.contains(f)) issues.add(new Issue("ERROR", "DATAFILE_INEXISTENTE", "El datafile " + f + " no existe en la base."));
        Long free = st.freeKb().get(s.destination());
        if (free == null)
            issues.add(new Issue("INFORMATIVA", "ESPACIO_DESCONOCIDO", "No se conoce el espacio libre en " + s.destination() + ". Comprueba la base para medirlo."));
        else if (free < 0)
            issues.add(new Issue("ERROR", "DESTINO_INEXISTENTE", "El destino " + s.destination() + " no existe o no es accesible en el servidor Oracle."));
        else if (free == 0)
            issues.add(new Issue("ERROR", "ESPACIO_AGOTADO", "El destino no tiene espacio libre (0 KB). Libera espacio antes de aprobar o ejecutar."));
        else {
            long estimate = estimatedBytes(s, st);
            if (estimate > free * 1024)
                issues.add(new Issue("ADVERTENCIA", "ESPACIO_INSUFICIENTE", "Espacio libre en destino: " + human(free * 1024) + "; tamano de los datafiles sin compresion: " + human(estimate) + "."));
            if (s.archivelogs() || s.controlfile() || s.spfile())
                issues.add(new Issue("ADVERTENCIA", "ESTIMACION_PARCIAL", "Espacio libre: " + human(free * 1024)
                    + ". El tamano de archived redo logs, control file y SPFILE no esta estimado; no se puede confirmar que todo el respaldo quepa."));
        }
        return issues;
    }

    /** Solo datafiles: excluye componentes, metadatos y variaciones durante la ejecucion. */
    public static long estimatedBytes(Strategy s, DatabaseStatus st) {
        return st.datafiles().stream().filter(d -> switch (s.scope()) {
            case "DATABASE" -> true;
            case "TABLESPACE" -> s.tablespaces().contains(d.rmanTablespace());
            case "DATAFILE" -> s.datafiles().contains(d.file());
            default -> false;
        }).mapToLong(Datafile::bytes).sum();
    }

    public static String human(long bytes) {
        if (bytes == 0) return "0 KB";
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
    }

    public static List<Integer> selectedDatafiles(Strategy s, DatabaseStatus st) {
        if (st == null) return List.of();
        return st.datafiles().stream().filter(d -> switch (s.scope()) {
            case "DATABASE" -> true;
            case "TABLESPACE" -> s.tablespaces().contains(d.rmanTablespace());
            case "DATAFILE" -> s.datafiles().contains(d.file());
            default -> false;
        }).map(Datafile::file).distinct().sorted().toList();
    }

    private static void comment(StringBuilder script, String text) {
        String ascii = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^\\x20-\\x7E]", "?");
        script.append("# ").append(ascii).append('\n');
    }
}
