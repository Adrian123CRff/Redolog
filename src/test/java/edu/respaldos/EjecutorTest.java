package edu.respaldos;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EjecutorTest {
    static final ZoneId Z = BackupService.ZONE_ID;
    @TempDir Path dir;
    final List<String> ran = new CopyOnWriteArrayList<>();

    static Instant at(String local) { return LocalDateTime.parse(local).atZone(Z).toInstant(); }

    Map<String, String> line(String code, String priority, String active, String approved, String days, String hours) throws Exception {
        String script = "# " + code + "\nRUN { BACKUP DATABASE; }\nEXIT;\n";
        Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(dir.resolve("scripts").resolve(code + ".rma"), script);
        var row = new LinkedHashMap<String, String>();
        row.put("CODIGO", code); row.put("SCRIPT", "scripts/" + code + ".rma"); row.put("ID", "id-" + code); row.put("ESTRATEGIA", "Estrategia " + code);
        row.put("CONTENEDOR", "rman-lab"); row.put("PRIORIDAD", priority); row.put("ACTIVA", active); row.put("APROBADA", approved);
        row.put("HUELLA", "SI".equals(approved) ? RmanScript.hash(script) : ""); row.put("FRECUENCIA", "SEMANAL"); row.put("INICIO", "2026-10-01");
        row.put("DIAS", days); row.put("HORAS", hours);
        return row;
    }

    Ejecutor ejecutor(boolean fail) {
        return new Ejecutor(dir, (container, code, script, log) -> {
            ran.add(code);
            String output = fail ? "RMAN-03009: failure of backup command\nORA-19504: failed to create file\n"
                : "piece handle=/opt/oracle/backup/FREE_" + code + ".bkp tag=X\nRecovery Manager complete.\n";
            Files.writeString(log, output);
            return new Rman.Result(fail ? 1 : 0, output, false);
        }, Z);
    }

    @Test void runsDueStrategiesByPriorityAndRecordsPieceAndLog() throws Exception {
        FlatCatalog.write(dir, "EXTERNO", List.of(
            line("RMA0001", "BAJA", "SI", "SI", "TUE,FRI", "02:00"),
            line("RMA0002", "ALTA", "SI", "SI", "TUE", "02:00"),
            line("RMA0003", "ALTA", "NO", "SI", "TUE", "02:00"),
            line("RMA0004", "ALTA", "SI", "NO", "TUE", "02:00"),
            line("RMA0005", "ALTA", "SI", "SI", "WED", "02:00")));
        // Martes 06/10/2026 a las 02:00.
        var results = ejecutor(false).tick(at("2026-10-06T01:59"), at("2026-10-06T02:01"));
        assertEquals(List.of("RMA0002", "RMA0001"), ran, "la prioridad alta va primero; inactivas, sin aprobar y de otro dia no corren");
        assertEquals(at("2026-10-06T02:00").toString(), results.get(0).get("PROGRAMADA"));
        var row = FlatCatalog.read(dir).rows().get(0);
        assertEquals("EXITOSO", row.get("RESULTADO"));
        assertEquals("/opt/oracle/backup/FREE_RMA0001.bkp", row.get("PIEZAS"));
        assertTrue(row.get("LOG").startsWith("ejecutor/logs/RMA0001_"));
        assertTrue(Files.readString(dir.resolve(row.get("LOG"))).contains("Recovery Manager complete."));
        assertEquals("EXTERNO", FlatCatalog.read(dir).scheduler(), "actualizar resultados no cambia el resto del catalogo");
        assertEquals(2, Ejecutor.results(dir).size());
        assertTrue(ejecutor(false).tick(at("2026-10-06T02:01"), at("2026-10-06T03:00")).isEmpty(), "no repite la misma hora");
    }

    @Test void failureIsRecordedWithRmanErrors() throws Exception {
        FlatCatalog.write(dir, "EXTERNO", List.of(line("RMA0001", "ALTA", "SI", "SI", "TUE", "02:00")));
        var result = ejecutor(true).now("rma0001".toUpperCase());
        assertEquals("FALLIDO", result.get("RESULTADO"));
        assertTrue(result.get("MENSAJE").contains("ORA-19504"));
        assertEquals("FALLIDO", FlatCatalog.read(dir).rows().get(0).get("RESULTADO"));
    }

    @Test void refusesAScriptChangedAfterApproval() throws Exception {
        FlatCatalog.write(dir, "EXTERNO", List.of(line("RMA0001", "ALTA", "SI", "SI", "TUE", "02:00")));
        Files.writeString(dir.resolve("scripts/RMA0001.rma"), "RUN { DELETE NOPROMPT BACKUP; }\n");
        var result = ejecutor(false).now("RMA0001");
        assertEquals("FALLIDO", result.get("RESULTADO"));
        assertTrue(result.get("MENSAJE").contains("cambio despues de aprobarse"));
        assertTrue(ran.isEmpty(), "no invoca RMAN");
    }

    @Test void columnsCannotBeBrokenBySeparators() throws Exception {
        var row = line("RMA0001", "ALTA", "SI", "SI", "TUE", "02:00");
        row.put("ESTRATEGIA", "Nombre | con\nsalto");
        FlatCatalog.write(dir, "INTERNO", List.of(row));
        var read = FlatCatalog.read(dir);
        assertEquals("Nombre / con salto", read.rows().get(0).get("ESTRATEGIA"));
        assertEquals("02:00", read.rows().get(0).get("HORAS"));
        assertEquals("INTERNO", read.scheduler());
    }
}
