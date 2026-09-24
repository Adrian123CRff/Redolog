package edu.respaldos;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

public final class Models {
    private Models() {}

    public static final List<String> PRIORITIES = List.of("ALTA", "MEDIA", "BAJA");
    public static final List<String> SCOPES = List.of("DATABASE", "TABLESPACE", "DATAFILE", "COMPONENTS");
    public static final List<String> METHODS = List.of("FULL", "LEVEL0", "LEVEL1", "CUMULATIVE");
    public static final List<String> FREQUENCIES = List.of("DIARIA", "SEMANAL", "INTERVALO");
    public static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    public static final String DEFAULT_DESTINATION = "/opt/oracle/backup";

    /** Objetivo de antigüedad máxima del último respaldo correcto (RPO) según la prioridad, en horas. */
    public static int rpoHours(String priority) {
        return switch (priority) { case "ALTA" -> 24; case "MEDIA" -> 72; default -> 168; };
    }

    public record Database(String id, String name, String container) {
        public Database {
            id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
            require(name != null && !name.isBlank() && name.length() <= 80, "Nombre de base requerido (hasta 80 caracteres).");
            require(container != null && container.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}"), "Nombre de contenedor no valido.");
            name = name.trim();
        }
    }

    /**
     * Estrategia = QUÉ + CÓMO + CUÁNDO + DESTINO. Los campos nuevos admiten null para leer
     * estrategias guardadas antes del enunciado; el constructor les asigna valores por defecto.
     */
    public record Strategy(String id,
                           // Información general
                           String name, String description, String databaseId, String responsible,
                           String priority, boolean enabled,
                           // Qué respaldar
                           String scope, List<String> tablespaces, List<Integer> datafiles,
                           boolean archivelogs, boolean controlfile, Boolean spfile,
                           // Cómo respaldar
                           String method, boolean compressed, boolean verifyAfter,
                           // Cuándo respaldar
                           String startDate, String frequency, List<String> days, List<String> times,
                           Integer intervalHours, Integer windowMinutes,
                           // Destino
                           String destination) {
        public Strategy {
            id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
            require(name != null && !name.isBlank() && name.length() <= 80, "Nombre de estrategia requerido (hasta 80 caracteres).");
            name = name.trim();
            description = clean(description, 300, "La descripcion admite hasta 300 caracteres.");
            responsible = clean(responsible, 80, "El responsable admite hasta 80 caracteres.");
            require(!hasControl(name), "El nombre no puede contener saltos de linea.");
            require(databaseId != null && !databaseId.isBlank(), "Selecciona una base de datos.");
            require(PRIORITIES.contains(priority), "Prioridad no valida.");

            // QUÉ
            if ("TABLESPACES".equals(scope)) scope = "TABLESPACE";
            require(SCOPES.contains(scope), "Alcance no valido.");
            tablespaces = tablespaces == null ? List.of() : tablespaces.stream().filter(Objects::nonNull).map(String::trim)
                .filter(v -> !v.isEmpty()).map(String::toUpperCase).distinct().toList();
            datafiles = datafiles == null ? List.of() : datafiles.stream().filter(Objects::nonNull).distinct().sorted().toList();
            if (!scope.equals("TABLESPACE")) tablespaces = List.of();
            if (!scope.equals("DATAFILE")) datafiles = List.of();
            require(!scope.equals("TABLESPACE") || !tablespaces.isEmpty(), "Indica al menos un tablespace.");
            require(tablespaces.size() <= 30, "Maximo 30 tablespaces.");
            for (String value : tablespaces)
                require(value.matches("(?:[A-Z][A-Z0-9_$#]{0,127}:)?[A-Z][A-Z0-9_$#]{0,127}"), "Tablespace no valido: " + value);
            require(!scope.equals("DATAFILE") || !datafiles.isEmpty(), "Indica al menos un numero de datafile.");
            require(datafiles.size() <= 50, "Maximo 50 datafiles.");
            for (int file : datafiles) require(file >= 1 && file <= 65533, "Numero de datafile no valido: " + file);
            // Las estrategias anteriores tenian una sola casilla "control file y SPFILE".
            spfile = spfile == null ? controlfile : spfile;
            require(!scope.equals("COMPONENTS") || archivelogs || controlfile || spfile,
                "Selecciona al menos un componente: archived redo logs, control file o SPFILE.");

            // CÓMO
            require(METHODS.contains(method), "Tipo de respaldo no valido.");
            if (scope.equals("COMPONENTS")) method = "FULL"; // los incrementales solo aplican a datafiles

            // CUÁNDO
            frequency = frequency == null ? "DIARIA" : frequency;
            require(FREQUENCIES.contains(frequency), "Frecuencia no valida.");
            startDate = startDate == null || startDate.isBlank() ? LocalDate.now().toString() : startDate;
            try { LocalDate.parse(startDate); } catch (Exception e) { throw new IllegalArgumentException("Fecha de inicio no valida (AAAA-MM-DD)."); }
            days = days == null || days.isEmpty() || frequency.equals("DIARIA") ? DAYS
                : days.stream().map(String::toUpperCase).distinct().sorted(Comparator.comparingInt(DAYS::indexOf)).toList();
            require(DAYS.containsAll(days), "Dia de ejecucion no valido.");
            times = times == null ? List.of() : times.stream().distinct().sorted().toList();
            for (String time : times) {
                require(time != null && time.matches("[0-2][0-9]:[0-5][0-9]"), "Usa horarios HH:mm.");
                LocalTime.parse(time);
            }
            if (frequency.equals("INTERVALO")) {
                require(intervalHours != null && intervalHours >= 1 && intervalHours <= 12, "El intervalo debe estar entre 1 y 12 horas.");
                require(times.size() <= 1, "Con frecuencia por intervalo indica una sola hora de inicio.");
            } else {
                intervalHours = null;
                require(times.size() <= 24, "Maximo 24 horarios por dia.");
            }
            require(windowMinutes == null || (windowMinutes >= 5 && windowMinutes <= 1440), "La ventana debe estar entre 5 y 1440 minutos.");
            // Una estrategia activa sin horarios se permite: el control preventivo la reporta (enunciado, seccion 11).

            // DESTINO: ruta dentro del servidor Oracle. Se incrusta en el script, por eso se restringe.
            destination = destination == null || destination.isBlank() ? DEFAULT_DESTINATION : destination.trim();
            while (destination.length() > 1 && destination.endsWith("/")) destination = destination.substring(0, destination.length() - 1);
            require(destination.matches("/[A-Za-z0-9_./-]{0,200}") && !destination.contains(".."),
                "Destino no valido: usa una ruta absoluta del servidor Oracle sin espacios ni '..'.");
        }

        /** Etiqueta RMAN estable derivada del nombre (maximo 30 caracteres). */
        public String tag() {
            String base = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
            if (base.isEmpty() || !Character.isLetter(base.charAt(0))) base = "E_" + base;
            return base.length() > 30 ? base.substring(0, 30).replaceAll("_+$", "") : base;
        }

        public boolean backsUpData() { return !scope.equals("COMPONENTS"); }

        public Strategy withArchivelogs(boolean value) {
            return new Strategy(id, name, description, databaseId, responsible, priority, enabled, scope, tablespaces, datafiles,
                value, controlfile, spfile, method, compressed, verifyAfter, startDate, frequency, days, times, intervalHours, windowMinutes, destination);
        }

        public Strategy withVerifyAfter(boolean value) {
            return new Strategy(id, name, description, databaseId, responsible, priority, enabled, scope, tablespaces, datafiles,
                archivelogs, controlfile, spfile, method, compressed, value, startDate, frequency, days, times, intervalHours, windowMinutes, destination);
        }
    }

    /** Evidencia de una ejecucion (seccion 10 del enunciado). */
    public record Execution(String id, String strategyId, String strategyName, String databaseId, String databaseName,
                            String operation, String backupType, String source, String plannedAt, String startedAt,
                            String finishedAt, String status, Integer exitCode, String message, String destination,
                            List<String> pieces, List<String> details, String scriptHash) {
        public Execution {
            // Estados usados antes del enunciado.
            status = switch (status == null ? "" : status) {
                case "CORRECTA" -> "EXITOSO";
                case "FALLIDA" -> "FALLIDO";
                case "OMITIDA" -> "OMITIDO";
                default -> status;
            };
            pieces = pieces == null ? List.of() : List.copyOf(pieces);
            details = details == null ? List.of() : List.copyOf(details);
        }

        public Execution finish(String status, Integer code, String message, List<String> pieces, List<String> details) {
            return new Execution(id, strategyId, strategyName, databaseId, databaseName, operation, backupType, source, plannedAt,
                startedAt, java.time.Instant.now().toString(), status, code, message, destination, pieces, details, scriptHash);
        }

        public boolean succeeded() { return status.equals("EXITOSO") || status.equals("CON_ADVERTENCIAS"); }
    }

    public record Approval(String strategyId, String scriptHash, String approvedAt, String approvedBy) {}

    public record Datafile(int file, String container, String tablespace, long bytes, String path) {
        /** Nombre del tablespace tal como lo espera RMAN (PDB:TABLESPACE fuera de la raiz). */
        public String rmanTablespace() {
            return container == null || container.isBlank() || container.equals("CDB$ROOT") || container.equals("-")
                ? tablespace : container + ":" + tablespace;
        }
    }

    /** Resultado persistido de la ultima comprobacion de una base. */
    public record DatabaseStatus(String databaseId, String checkedAt, boolean reachable, boolean rmanAvailable,
                                 String version, String dbName, String openMode, String logMode, List<String> pdbs,
                                 List<Datafile> datafiles, int archivedLogs, Map<String, Long> freeKb, String message) {
        public DatabaseStatus {
            pdbs = pdbs == null ? List.of() : pdbs;
            datafiles = datafiles == null ? List.of() : datafiles;
            freeKb = freeKb == null ? Map.of() : freeKb;
        }

        public List<String> tablespaces() {
            return datafiles.stream().map(Datafile::rmanTablespace).distinct().sorted().toList();
        }
    }

    /** Resultado de validar una estrategia antes de aprobarla. level: ERROR, ADVERTENCIA, RECOMENDACION, INFORMATIVA. */
    public record Issue(String level, String code, String message) {}

    /**
     * Condicion detectada por el control preventivo. level: ALERTA, ADVERTENCIA, RECOMENDACION, INFORMATIVA.
     * action: accion que el administrador puede aplicar; la herramienta nunca la aplica sola.
     */
    public record Alert(String level, String code, String title, String detail, String databaseId, String strategyId,
                        String action, String actionLabel) {}

    /** Bitacora de acciones del administrador y del sistema. */
    public record Event(String id, String at, String type, String databaseId, String strategyId, String detail) {}

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static String clean(String value, int max, String message) {
        if (value == null || value.isBlank()) return null;
        require(value.length() <= max, message);
        return value.trim();
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(c -> c < 32 || c == 127);
    }
}
