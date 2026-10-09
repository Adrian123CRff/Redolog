package edu.respaldos;

import edu.respaldos.Models.*;
import java.nio.file.*;
import java.util.Collection;

/**
 * Mantiene un archivo .rma por estrategia con el script vigente, con el nombre de su codigo
 * (RMA0001.rma, RMA0002.rma...). El ejecutor lo invoca desde el catalogo plano. La evidencia de
 * cada ejecucion sigue siendo su propia copia.
 */
public final class ScriptStore {
    private final Path directory;

    public ScriptStore(Path directory) { this.directory = directory; }

    /** Las estrategias anteriores al codigo usan el nombre antiguo: ETIQUETA-contenedor.rma. */
    public String fileName(Strategy s, Database db) {
        return s.scriptCode() != null ? s.scriptCode() + Rman.SCRIPT_EXT : legacyName(s, db);
    }

    private static String legacyName(Strategy s, Database db) { return s.tag() + "-" + db.container() + Rman.SCRIPT_EXT; }

    public Path path(Strategy s, Database db) { return directory.resolve(fileName(s, db)); }

    public Path write(Strategy s, Database db, String script) throws Exception {
        Files.createDirectories(directory);
        if (s.scriptCode() != null) Files.deleteIfExists(directory.resolve(legacyName(s, db)));
        return Files.writeString(path(s, db), script);
    }

    public void delete(Strategy s, Database db) throws Exception {
        Files.deleteIfExists(path(s, db));
        Files.deleteIfExists(directory.resolve(legacyName(s, db)));
    }

    /** Siguiente codigo libre: RMA0001, RMA0002... (no reutiliza los de estrategias existentes). */
    public static String nextCode(Collection<Strategy> strategies) {
        int max = strategies.stream().map(Strategy::scriptCode).filter(c -> c != null)
            .mapToInt(c -> Integer.parseInt(c.substring(3))).max().orElse(0);
        return String.format("RMA%04d", max + 1);
    }
}
