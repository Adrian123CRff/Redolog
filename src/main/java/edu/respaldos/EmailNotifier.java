package edu.respaldos;

import edu.respaldos.Models.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Avisa por correo al DBA de la base cuando una ejecucion termina mal. El envio ocurre en un hilo propio con
 * reintentos para no retrasar el cierre del respaldo, y cada resultado queda en la bitacora.
 */
public final class EmailNotifier implements Notifier {
    private static final Set<String> NOTIFIABLE = Set.of("FALLIDO", "INCIERTO", "CON_ADVERTENCIAS");
    private static final int MAX_DETAILS = 10;

    private final Catalog catalog;
    private final Mailer mailer;
    private final Path runtime;
    private final int attempts;
    private final Duration backoff;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "aviso-correo"); thread.setDaemon(true); return thread;
    });

    public EmailNotifier(Catalog catalog, Mailer mailer, Path runtime, int attempts, Duration backoff) {
        this.catalog = catalog; this.mailer = mailer; this.runtime = runtime; this.attempts = attempts; this.backoff = backoff;
    }

    @Override public void notifyFinished(Execution e) {
        if (!NOTIFIABLE.contains(e.status())) return;
        try { sender.submit(() -> deliver(e)); }
        catch (RejectedExecutionException closed) { System.err.println("Aviso omitido: el notificador ya se cerro."); }
    }

    private void deliver(Execution e) {
        try {
            var db = catalog.database(e.databaseId());
            if (db.dbaEmail() == null) { log(db, e, "NOTIFICACION_OMITIDA", "La base no tiene correo del DBA; no se envio aviso de " + e.status() + "."); return; }
            String subject = singleLine("[RMAN] " + e.status() + " - " + e.strategyName() + " en " + db.name());
            String lastError = "sin detalle";
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    mailer.send(db.dbaEmail(), subject, body(e, db));
                    log(db, e, "NOTIFICACION_ENVIADA", "Enviado a " + db.dbaEmail() + " en el intento " + attempt + ".");
                    return;
                } catch (Exception failure) {
                    lastError = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                    if (attempt < attempts) pause();
                }
            }
            log(db, e, "NOTIFICACION_FALLIDA", "No se pudo enviar a " + db.dbaEmail() + " tras " + attempts + " intentos: " + lastError);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception unexpected) {
            System.err.println("No se pudo avisar la ejecucion " + e.id() + ": " + unexpected);
        }
    }

    /** Un asunto con saltos de linea permitiria inyectar cabeceras de correo. */
    static String singleLine(String text) { return text.replaceAll("\\p{Cntrl}+", " "); }

    private void pause() throws InterruptedException {
        if (!backoff.isZero()) Thread.sleep(backoff.toMillis());
    }

    private String body(Execution e, Database db) {
        var lines = new ArrayList<String>();
        lines.add("Base de datos: " + db.name() + " (" + db.container() + ")");
        lines.add("Estrategia: " + e.strategyName());
        lines.add("Tipo: " + e.backupType());
        lines.add("Estado: " + e.status());
        lines.add("Inicio: " + e.startedAt());
        lines.add("Fin: " + e.finishedAt());
        lines.add("Mensaje: " + e.message());
        if (!e.details().isEmpty()) {
            lines.add("");
            lines.add("Detalle de RMAN:");
            e.details().stream().limit(MAX_DETAILS).forEach(d -> lines.add("  " + d));
            if (e.details().size() > MAX_DETAILS) lines.add("  ... y " + (e.details().size() - MAX_DETAILS) + " mas");
        }
        lines.add("");
        lines.add("Registro completo: " + runtime.resolve("executions").resolve(e.id()).resolve("output.log"));
        return String.join("\n", lines);
    }

    private void log(Database db, Execution e, String type, String detail) throws Exception {
        catalog.log(new Event(UUID.randomUUID().toString(), Instant.now().toString(), type, db.id(), e.strategyId(), detail));
    }

    /** Espera a que terminen los envios pendientes. */
    @Override public void close() {
        sender.shutdown();
        try { sender.awaitTermination(30, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
