package edu.respaldos;

import edu.respaldos.Models.*;
import java.nio.file.*;

/**
 * Mantiene un archivo .rma por estrategia con el script vigente (por ejemplo EST001_DATOS-rman-lab.rma),
 * para revisarlo o ejecutarlo a mano. La evidencia de cada ejecucion sigue siendo su propia copia.
 */
public final class ScriptStore {
    private final Path directory;

    public ScriptStore(Path directory) { this.directory = directory; }

    public String fileName(Strategy s, Database db) { return s.tag() + "-" + db.container() + Rman.SCRIPT_EXT; }

    public Path write(Strategy s, Database db, String script) throws Exception {
        Files.createDirectories(directory);
        return Files.writeString(directory.resolve(fileName(s, db)), script);
    }

    public void delete(Strategy s, Database db) throws Exception {
        Files.deleteIfExists(directory.resolve(fileName(s, db)));
    }
}
