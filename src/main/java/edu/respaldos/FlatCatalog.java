package edu.respaldos;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Catalogo de estrategias en archivo plano (runtime/catalogo-estrategias.txt), una linea por estrategia.
 * Lo escribe la aplicacion y lo lee el ejecutor; el ejecutor actualiza las columnas de su ultima ejecucion.
 * El auditor lo puede abrir con cualquier editor. El catalogo H2 sigue siendo la fuente de la aplicacion.
 */
public final class FlatCatalog {
    public static final String FILE = "catalogo-estrategias.txt";
    public static final List<String> COLUMNS = List.of("CODIGO", "SCRIPT", "ID", "ESTRATEGIA", "BASE", "CONTENEDOR", "PRIORIDAD",
        "ELEMENTOS", "TIPO", "ACTIVA", "APROBADA", "HUELLA", "FRECUENCIA", "INICIO", "DIAS", "HORAS", "INTERVALO_H", "DESTINO",
        "ULTIMA_EJECUCION", "RESULTADO", "PIEZAS", "LOG");
    /** Columnas que cambian con cada ejecucion; las actualiza quien ejecuto (aplicacion o ejecutor). */
    public static final List<String> RESULT_COLUMNS = List.of("ULTIMA_EJECUCION", "RESULTADO", "PIEZAS", "LOG");
    // La aplicacion escribe desde varios hilos; el ejecutor es otro proceso: hacen falta los dos candados.
    private static final ReentrantLock LOCAL = new ReentrantLock();

    private FlatCatalog() {}

    public record Content(String scheduler, List<Map<String, String>> rows) {}

    /** Valor seguro para una columna: sin separadores ni saltos de linea. */
    public static String clean(Object value) {
        if (value == null) return "";
        return value.toString().replace('|', '/').replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    public static void write(Path runtime, String scheduler, List<Map<String, String>> rows) throws Exception {
        locked(runtime, () -> { save(runtime, scheduler, rows); return null; });
    }

    public static Content read(Path runtime) throws Exception {
        Path file = runtime.resolve(FILE);
        if (!Files.exists(file)) return new Content(null, List.of());
        String scheduler = null;
        List<String> header = null;
        var rows = new ArrayList<Map<String, String>>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            if (line.startsWith("#")) {
                if (line.startsWith("# Planificador:")) scheduler = line.substring("# Planificador:".length()).trim().split("\\s+")[0];
                continue;
            }
            var cells = line.split("\\|", -1);
            if (header == null) { header = List.of(cells); continue; }
            var row = new LinkedHashMap<String, String>();
            for (int i = 0; i < header.size(); i++) row.put(header.get(i), i < cells.length ? cells[i] : "");
            rows.add(row);
        }
        return new Content(scheduler, rows);
    }

    /** El ejecutor registra el resultado en la linea de la estrategia sin tocar el resto del catalogo. */
    public static void updateResult(Path runtime, String code, Map<String, String> values) throws Exception {
        locked(runtime, () -> {
            var content = read(runtime);
            var rows = new ArrayList<Map<String, String>>();
            for (var row : content.rows()) {
                var copy = new LinkedHashMap<>(row);
                if (code.equals(row.get("CODIGO"))) RESULT_COLUMNS.forEach(c -> { if (values.containsKey(c)) copy.put(c, clean(values.get(c))); });
                rows.add(copy);
            }
            save(runtime, content.scheduler(), rows);
            return null;
        });
    }

    private static void save(Path runtime, String scheduler, List<Map<String, String>> rows) throws Exception {
        Files.createDirectories(runtime);
        var text = new StringBuilder();
        text.append("# Catalogo de estrategias de respaldo RMAN - una linea por estrategia, columnas separadas por |\n");
        text.append("# Lo genera el Gestor RMAN; el ejecutor lo lee y actualiza ULTIMA_EJECUCION, RESULTADO, PIEZAS y LOG.\n");
        text.append("# Planificador: ").append(scheduler == null ? "INTERNO" : scheduler)
            .append(" (INTERNO: ejecuta la aplicacion; EXTERNO: ejecuta el programa Ejecutor)\n");
        text.append(String.join("|", COLUMNS)).append('\n');
        for (var row : rows) text.append(String.join("|", COLUMNS.stream().map(c -> clean(row.get(c))).toList())).append('\n');
        Path file = runtime.resolve(FILE), temp = runtime.resolve(FILE + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    private interface Action<T> { T run() throws Exception; }

    private static <T> T locked(Path runtime, Action<T> action) throws Exception {
        LOCAL.lock();
        try {
            Files.createDirectories(runtime);
            try (var channel = FileChannel.open(runtime.resolve(FILE + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                return action.run();
            }
        } finally { LOCAL.unlock(); }
    }
}
