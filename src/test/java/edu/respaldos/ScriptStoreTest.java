package edu.respaldos;

import edu.respaldos.Models.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ScriptStoreTest {
    @TempDir Path dir;
    final Database db = new Database("d1", "Laboratorio", "rman-lab");
    final Strategy strategy = new Strategy("s1", "EST001 - Datos", null, "d1", null, "ALTA", true,
        "DATABASE", null, null, true, true, true, "FULL", false, false, "2100-01-01", "DIARIA", null, List.of("02:00"), null, null, null);

    @Test void fileNameCombinesStrategyTagAndDatabaseWithTheRmaExtension() {
        assertEquals(strategy.tag() + "-rman-lab.rma", new ScriptStore(dir).fileName(strategy, db));
        assertEquals(".rma", Rman.SCRIPT_EXT);
    }

    @Test void writesAndReplacesTheCurrentScriptAndDeletesItWithTheStrategy() throws Exception {
        var store = new ScriptStore(dir.resolve("scripts"));
        var file = store.write(strategy, db, "run { backup database; }");
        assertEquals("run { backup database; }", Files.readString(file));
        store.write(strategy, db, "run { backup database plus archivelog; }");
        assertEquals("run { backup database plus archivelog; }", Files.readString(file));
        store.delete(strategy, db);
        assertFalse(Files.exists(file));
        assertDoesNotThrow(() -> store.delete(strategy, db), "borrar dos veces no es un error");
    }

    @Test void readScriptPrefersRmaAndFallsBackToTheOldRmanExtension() throws Exception {
        Files.writeString(dir.resolve("script.rman"), "viejo");
        assertEquals("viejo", Rman.readScript(dir, "script"));
        Files.writeString(dir.resolve("script.rma"), "nuevo");
        assertEquals("nuevo", Rman.readScript(dir, "script"));
        assertEquals("", Rman.readScript(dir, "verify"));
    }
}
