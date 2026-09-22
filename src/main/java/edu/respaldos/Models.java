package edu.respaldos;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class Models {
    private Models() {}
    public record Database(String id, String name, String container) {
        public Database {
            id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
            require(name != null && !name.isBlank() && name.length() <= 80, "Nombre de base requerido (hasta 80 caracteres).");
            require(container != null && container.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}"), "Nombre de contenedor no valido.");
            name = name.trim();
        }
    }
    public record Strategy(String id, String name, String databaseId, String scope, List<String> tablespaces,
                           String method, boolean archivelogs, boolean controlfile, String priority,
                           List<String> times, boolean enabled) {
        public Strategy {
            id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
            require(name != null && !name.isBlank() && name.length() <= 80, "Nombre de estrategia requerido.");
            name = name.trim();
            require(databaseId != null && !databaseId.isBlank(), "Selecciona una base de datos.");
            require(List.of("DATABASE", "TABLESPACE").contains(scope), "Alcance no valido.");
            require(List.of("FULL", "LEVEL0", "LEVEL1", "CUMULATIVE").contains(method), "Metodo no valido.");
            require(List.of("ALTA", "MEDIA", "BAJA").contains(priority), "Prioridad no valida.");
            tablespaces = tablespaces == null ? List.of() : tablespaces.stream().map(String::trim).map(String::toUpperCase).distinct().toList();
            require(!scope.equals("TABLESPACE") || !tablespaces.isEmpty(), "Indica al menos un tablespace.");
            require(tablespaces.size() <= 30, "Maximo 30 tablespaces.");
            for (String value : tablespaces) require(value.matches("(?:[A-Z][A-Z0-9_]{0,29}:)?[A-Z][A-Z0-9_]{0,29}"), "Tablespace no valido: " + value);
            times = times == null ? List.of() : times.stream().distinct().sorted().toList();
            require(times.size() <= 24, "Maximo 24 horarios diarios.");
            for (String time : times) {
                require(time != null && time.matches("[0-2][0-9]:[0-5][0-9]"), "Usa horarios HH:mm.");
                LocalTime.parse(time);
            }
            require(!enabled || !times.isEmpty(), "Agrega un horario antes de activar la estrategia.");
        }
    }
    public record Execution(String id, String strategyId, String strategyName, String databaseId,
                            String operation, String source, String plannedAt, String startedAt,
                            String finishedAt, String status, Integer exitCode, String message) {}

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
