package edu.respaldos;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import edu.respaldos.Models.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

public final class Main {
    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private static final int MAX_BODY = 65_536;

    public static void main(String[] args) throws Exception {
        // Modo local: RMAN real via Docker, solo en 127.0.0.1. Modo simulacion: demostracion publica
        // sin Oracle (Render u otro hosting); es el unico que puede escuchar fuera de la maquina.
        boolean simulation = "simulacion".equalsIgnoreCase(System.getProperty("app.mode", Objects.requireNonNullElse(System.getenv("GESTOR_MODO"), "local")));
        int port = Integer.parseInt(System.getProperty("app.port", simulation ? Objects.requireNonNullElse(System.getenv("PORT"), "8787") : "8787"));
        Path runtime = Path.of(System.getProperty("app.data", simulation ? "runtime-simulacion" : "runtime")).toAbsolutePath();
        Files.createDirectories(runtime);
        // Un solo proceso es dueno del catalogo local y del planificador.
        var lockChannel = java.nio.channels.FileChannel.open(runtime.resolve("app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var lock = lockChannel.tryLock();
        if (lock == null) throw new IllegalStateException("Ya hay una instancia usando este catalogo.");
        var catalog = new Catalog(runtime.resolve("catalog"), JSON);
        var simulated = simulation ? new SimulatedRman(2500) : null;
        if (simulation) Demo.seed(catalog, simulated, runtime);
        else if (catalog.databases().isEmpty()) {
            var db = new Database(null, "Laboratorio Oracle", "rman-lab"); catalog.save(db);
            catalog.save(new Strategy(null, "EST001 - Datos del laboratorio", "Tablespace de pedidos del laboratorio; datos criticos del caso de estudio.",
                db.id(), "DBA del grupo", "ALTA", false, "TABLESPACE", List.of("FREEPDB1:LAB_DATOS"), List.of(), true, true, true,
                "FULL", true, false, null, "DIARIA", null, List.of("13:00", "15:00", "18:00", "21:00"), null, 30, Models.DEFAULT_DESTINATION));
        }
        var service = new BackupService(catalog, runtime, simulation ? simulated : new Rman());
        var server = HttpServer.create(new InetSocketAddress(simulation ? "0.0.0.0" : "127.0.0.1", port), 0);
        var httpPool = Executors.newFixedThreadPool(8); server.setExecutor(httpPool);
        String origin = "http://127.0.0.1:" + port;
        server.createContext("/", exchange -> {
            try {
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                String host = exchange.getRequestHeaders().getFirst("Host");
                if (!simulation && !Set.of("127.0.0.1:" + port, "localhost:" + port).contains(host)) { send(exchange, 403, Map.of("error", "Host no permitido.")); return; }
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/api/")) {
                    String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
                    var allowed = simulation ? Set.of("http://" + host, "https://" + host) : Set.of(origin, "http://localhost:" + port);
                    if (requestOrigin != null && !allowed.contains(requestOrigin)) { send(exchange, 403, Map.of("error", "Origen no permitido.")); return; }
                    api(exchange, path, catalog, service, runtime, simulation);
                } else {
                    if (!exchange.getRequestMethod().equals("GET")) { send(exchange, 405, Map.of("error", "Metodo no permitido.")); return; }
                    String resource = switch (path) { case "/" -> "index.html"; case "/app.js" -> "app.js"; case "/style.css" -> "style.css"; case "/lucide.min.js" -> "lucide.min.js"; default -> null; };
                    if (resource == null) { send(exchange, 404, Map.of("error", "No encontrado.")); return; }
                    try (var stream = Main.class.getResourceAsStream("/web/" + resource)) {
                        if (stream == null) { send(exchange, 404, Map.of("error", "Recurso no encontrado.")); return; }
                        String type = resource.endsWith(".css") ? "text/css" : resource.endsWith(".js") ? "text/javascript" : "text/html";
                        bytes(exchange, 200, type + "; charset=utf-8", stream.readAllBytes());
                    }
                }
            } catch (IllegalStateException e) { send(exchange, 409, Map.of("error", e.getMessage())); }
            catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) { send(exchange, 400, Map.of("error", message(e))); }
            catch (Exception e) { e.printStackTrace(); send(exchange, 500, Map.of("error", "La operacion no pudo completarse: " + e.getClass().getSimpleName())); }
            finally { exchange.close(); }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { server.stop(1); httpPool.shutdown(); service.close(); lock.release(); lockChannel.close(); } catch (Exception ignored) {} }));
        server.start();
        System.out.println(simulation ? "Gestor RMAN en MODO SIMULACION (sin Oracle) en el puerto " + port : "Gestor RMAN disponible en " + origin);
    }

    private static void api(HttpExchange x, String path, Catalog catalog, BackupService service, Path runtime, boolean simulation) throws Exception {
        String method = x.getRequestMethod();
        if (method.equals("GET")) {
            if (path.equals("/api/state")) { send(x, 200, service.state()); return; }
            if (path.startsWith("/api/executions/")) {
                String id = path.substring("/api/executions/".length()); var e = catalog.execution(id);
                var dir = runtime.resolve("executions").resolve(e.id());
                var body = new LinkedHashMap<String, Object>();
                body.put("execution", e);
                body.put("script", Rman.readTail(dir.resolve("script.rman"), 100_000));
                body.put("log", Rman.readTail(dir.resolve("output.log"), 250_000));
                body.put("verifyScript", Rman.readTail(dir.resolve("verify.rman"), 100_000));
                body.put("verifyLog", Rman.readTail(dir.resolve("verify.log"), 250_000));
                send(x, 200, body); return;
            }
        }
        if (method.equals("POST")) {
            Models.require(Optional.ofNullable(x.getRequestHeaders().getFirst("Content-Type")).orElse("").startsWith("application/json"), "Content-Type debe ser application/json.");
            byte[] body = x.getRequestBody().readNBytes(MAX_BODY + 1); Models.require(body.length <= MAX_BODY, "Solicitud demasiado grande.");
            JsonNode node = JSON.readTree(body); Models.require(node != null && node.isObject(), "Se esperaba un objeto JSON.");
            // La demostracion publica limita cuanto puede crear un visitante.
            if (simulation && path.equals("/api/databases")) Models.require(catalog.databases().size() < 10, "La demostracion admite hasta 10 bases.");
            if (simulation && path.equals("/api/strategies") && (!node.hasNonNull("id") || node.get("id").asText().isBlank()))
                Models.require(catalog.strategies().size() < 30, "La demostracion admite hasta 30 estrategias.");
            switch (path) {
                case "/api/databases" -> { var db = JSON.treeToValue(node, Database.class); catalog.save(db); send(x, 200, db); }
                case "/api/strategies" -> send(x, 200, service.save(JSON.treeToValue(node, Strategy.class)));
                case "/api/preview" -> send(x, 200, service.preview(JSON.treeToValue(node.get("strategy"), Strategy.class)));
                case "/api/approve" -> send(x, 200, service.approve(node.path("strategyId").asText(), node.path("approvedBy").asText("")));
                case "/api/run" -> send(x, 202, Map.of("id", service.run(node.path("strategyId").asText(), node.path("operation").asText("BACKUP"), "MANUAL", null)));
                case "/api/recommendations/apply" -> send(x, 200, service.apply(node.path("strategyId").asText(), node.path("action").asText()));
                case "/api/release" -> { service.release(node.path("databaseId").asText()); send(x, 200, Map.of("ok", true)); }
                case "/api/diagnose" -> send(x, 200, service.diagnose(node.path("databaseId").asText()));
                case "/api/strategies/delete" -> { service.delete(node.path("id").asText()); send(x, 200, Map.of("ok", true)); }
                default -> send(x, 404, Map.of("error", "Ruta no encontrada."));
            }
            return;
        }
        send(x, 404, Map.of("error", "Ruta no encontrada."));
    }

    /** Jackson envuelve las validaciones del modelo; se devuelve el mensaje original. */
    private static String message(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String text = t instanceof IllegalArgumentException ? t.getMessage() : e.getMessage();
        if (text == null) return "Datos no validos.";
        return t instanceof IllegalArgumentException ? text : text.split("\n")[0];
    }

    private static void send(HttpExchange x, int status, Object value) throws java.io.IOException { bytes(x, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(value)); }

    private static void bytes(HttpExchange x, int status, String type, byte[] data) throws java.io.IOException {
        x.getResponseHeaders().set("Content-Type", type); x.sendResponseHeaders(status, data.length); x.getResponseBody().write(data);
    }
}
