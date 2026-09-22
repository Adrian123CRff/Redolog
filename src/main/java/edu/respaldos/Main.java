package edu.respaldos;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import edu.respaldos.Models.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

public final class Main {
    private static final ObjectMapper JSON=new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,true);
    private static final int MAX_BODY=65_536;
    public static void main(String[] args) throws Exception {
        int port=Integer.parseInt(System.getProperty("app.port","8787"));
        Path runtime=Path.of(System.getProperty("app.data","runtime")).toAbsolutePath();
        Files.createDirectories(runtime);
        // One process owns the local catalog and scheduler at a time.
        var lockChannel=java.nio.channels.FileChannel.open(runtime.resolve("app.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        var lock=lockChannel.tryLock();
        if(lock==null) throw new IllegalStateException("Ya hay una instancia usando este catalogo.");
        var catalog=new Catalog(runtime.resolve("catalog"),JSON);
        if(catalog.databases().isEmpty()) {
            var db=new Database(null,"Laboratorio Oracle","rman-lab"); catalog.save(db);
            catalog.save(new Strategy(null,"EST001 - Datos del laboratorio",db.id(),"TABLESPACE",List.of("FREEPDB1:LAB_DATOS"),"FULL",true,true,"ALTA",List.of("13:00","15:00","18:00","21:00"),false));
        }
        var service=new BackupService(catalog,runtime,new Rman());
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        var httpPool=Executors.newFixedThreadPool(8); server.setExecutor(httpPool);
        String origin="http://127.0.0.1:"+port;
        server.createContext("/",exchange -> {
            try {
                exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
                exchange.getResponseHeaders().set("Cache-Control","no-store");
                String host=exchange.getRequestHeaders().getFirst("Host");
                if(!Set.of("127.0.0.1:"+port,"localhost:"+port).contains(host)) { send(exchange,403,Map.of("error","Host no permitido.")); return; }
                String path=exchange.getRequestURI().getPath();
                if(path.startsWith("/api/")) {
                    String requestOrigin=exchange.getRequestHeaders().getFirst("Origin");
                    if(requestOrigin!=null && !Set.of(origin,"http://localhost:"+port).contains(requestOrigin)) {send(exchange,403,Map.of("error","Origen no permitido."));return;}
                    api(exchange,path,catalog,service,runtime);
                } else {
                    if(!exchange.getRequestMethod().equals("GET")) {send(exchange,405,Map.of("error","Metodo no permitido."));return;}
                    String resource=switch(path) {case "/"->"index.html";case "/app.js"->"app.js";case "/style.css"->"style.css";case "/lucide.min.js"->"lucide.min.js";default->null;};
                    if(resource==null) {send(exchange,404,Map.of("error","No encontrado."));return;}
                    try(var stream=Main.class.getResourceAsStream("/web/"+resource)) {
                        if(stream==null) {send(exchange,404,Map.of("error","Recurso no encontrado."));return;}
                        String type=resource.endsWith(".css")?"text/css":resource.endsWith(".js")?"text/javascript":"text/html";
                        bytes(exchange,200,type+"; charset=utf-8",stream.readAllBytes());
                    }
                }
            } catch(IllegalStateException e) { send(exchange,409,Map.of("error",e.getMessage())); }
            catch(IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) { send(exchange,400,Map.of("error",e.getMessage()==null?"Datos no validos.":e.getMessage())); }
            catch(Exception e) { e.printStackTrace(); send(exchange,500,Map.of("error","La operacion no pudo completarse: "+e.getClass().getSimpleName())); }
            finally {exchange.close();}
        });
        Runtime.getRuntime().addShutdownHook(new Thread(()->{try {server.stop(1);httpPool.shutdown();service.close();lock.release();lockChannel.close();}catch(Exception ignored){}}));
        server.start(); System.out.println("Gestor RMAN disponible en "+origin);
    }
    private static void api(HttpExchange x,String path,Catalog catalog,BackupService service,Path runtime) throws Exception {
        String method=x.getRequestMethod();
        if(method.equals("GET")) {
            if(path.equals("/api/state")) {send(x,200,service.state());return;}
            if(path.startsWith("/api/executions/")) {
                String id=path.substring("/api/executions/".length()); var e=catalog.execution(id);
                var dir=runtime.resolve("executions").resolve(e.id());
                send(x,200,Map.of("execution",e,"script",Rman.readTail(dir.resolve("script.rman"),100_000),"log",Rman.readTail(dir.resolve("output.log"),250_000)));return;
            }
        }
        if(method.equals("POST")) {
            Models.require(Optional.ofNullable(x.getRequestHeaders().getFirst("Content-Type")).orElse("").startsWith("application/json"),"Content-Type debe ser application/json.");
            byte[] body=x.getRequestBody().readNBytes(MAX_BODY+1); Models.require(body.length<=MAX_BODY,"Solicitud demasiado grande.");
            JsonNode node=JSON.readTree(body); Models.require(node!=null && node.isObject(),"Se esperaba un objeto JSON.");
            if(path.equals("/api/databases")) {
                var db=JSON.treeToValue(node,Database.class); catalog.save(db); send(x,200,db);return;
            }
            if(path.equals("/api/strategies")) {
                var s=JSON.treeToValue(node,Strategy.class); service.save(s);send(x,200,s);return;
            }
            if(path.equals("/api/preview")) {
                var s=JSON.treeToValue(node.get("strategy"),Strategy.class); catalog.database(s.databaseId());
                send(x,200,Map.of("script",Rman.script(s,node.path("operation").asText("BACKUP"))));return;
            }
            if(path.equals("/api/run")) {
                send(x,202,Map.of("id",service.run(node.path("strategyId").asText(),node.path("operation").asText("BACKUP"),"MANUAL",null)));return;
            }
            if(path.equals("/api/diagnose")) {send(x,200,service.diagnose(node.path("databaseId").asText()));return;}
            if(path.equals("/api/strategies/delete")) {service.delete(node.path("id").asText());send(x,200,Map.of("ok",true));return;}
        }
        send(x,404,Map.of("error","Ruta no encontrada."));
    }
    private static void send(HttpExchange x,int status,Object value) throws java.io.IOException {bytes(x,status,"application/json; charset=utf-8",JSON.writeValueAsBytes(value));}
    private static void bytes(HttpExchange x,int status,String type,byte[] data) throws java.io.IOException {
        x.getResponseHeaders().set("Content-Type",type);x.sendResponseHeaders(status,data.length);x.getResponseBody().write(data);
    }
}
