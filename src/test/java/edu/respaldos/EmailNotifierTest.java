package edu.respaldos;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.respaldos.Models.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EmailNotifierTest {
    record Sent(String to, String subject, String body) {}

    static class Recorder implements Mailer {
        final List<Sent> sent = new CopyOnWriteArrayList<>();
        int failFirst;
        int attempts;
        @Override public synchronized void send(String to, String subject, String body) throws Exception {
            attempts++;
            if (attempts <= failFirst) throw new IllegalStateException("SMTP caido");
            sent.add(new Sent(to, subject, body));
        }
    }

    @TempDir Path dir;
    Catalog catalog;
    final Recorder mailer = new Recorder();
    EmailNotifier notifier;

    @BeforeEach void setup() throws Exception {
        catalog = new Catalog(dir.resolve("catalog"), new ObjectMapper());
        notifier = new EmailNotifier(catalog, mailer, dir, 3, Duration.ZERO);
    }

    Database database(String name, String email) throws Exception {
        var db = new Database(null, name, "c-" + UUID.randomUUID().toString().substring(0, 8), email);
        catalog.save(db);
        return db;
    }

    Execution execution(Database db, String status, String message, List<String> details) {
        return new Execution("e1", "s1", "EST001", db.id(), db.name(), "BACKUP", "Completo | Base completa", "HORARIO", null,
            "2026-10-04T18:00:00Z", "2026-10-04T18:05:00Z", status, 1, message, "/opt/oracle/backup", List.of(), details, "hash")
            ;
    }

    List<String> eventTypes() throws Exception { return catalog.events().stream().map(Event::type).toList(); }

    @Test void failedExecutionSendsOneMailToTheDbaWithTheRmanError() throws Exception {
        var db = database("Laboratorio", "dba@una.cr");
        notifier.notifyFinished(execution(db, "FALLIDO", "RMAN informo un error: RMAN-06019", List.of("RMAN-06019: could not translate tablespace name")));
        notifier.close();
        assertEquals(1, mailer.sent.size());
        var mail = mailer.sent.getFirst();
        assertEquals("dba@una.cr", mail.to());
        assertTrue(mail.subject().contains("FALLIDO") && mail.subject().contains("EST001") && mail.subject().contains("Laboratorio"));
        assertTrue(mail.body().contains("RMAN-06019"));
        assertTrue(eventTypes().contains("NOTIFICACION_ENVIADA"));
    }

    @Test void uncertainAndWarningExecutionsAlsoNotify() throws Exception {
        var db = database("Laboratorio", "dba@una.cr");
        notifier.notifyFinished(execution(db, "INCIERTO", "Tiempo agotado", List.of()));
        notifier.notifyFinished(execution(db, "CON_ADVERTENCIAS", "Con advertencias", List.of("RMAN-08XXX: WARNING")));
        notifier.close();
        assertEquals(2, mailer.sent.size());
    }

    @Test void successfulOrSkippedExecutionsDoNotNotify() throws Exception {
        var db = database("Laboratorio", "dba@una.cr");
        notifier.notifyFinished(execution(db, "EXITOSO", "ok", List.of()));
        notifier.notifyFinished(execution(db, "OMITIDO", "base ocupada", List.of()));
        notifier.close();
        assertTrue(mailer.sent.isEmpty());
        assertTrue(eventTypes().isEmpty());
    }

    @Test void databaseWithoutDbaEmailRecordsASkippedEventInsteadOfSending() throws Exception {
        var db = database("Sin correo", null);
        notifier.notifyFinished(execution(db, "FALLIDO", "error", List.of()));
        notifier.close();
        assertTrue(mailer.sent.isEmpty());
        assertTrue(eventTypes().contains("NOTIFICACION_OMITIDA"));
    }

    @Test void retriesAndSucceedsWhenSmtpRecovers() throws Exception {
        mailer.failFirst = 2;
        var db = database("Laboratorio", "dba@una.cr");
        notifier.notifyFinished(execution(db, "FALLIDO", "error", List.of()));
        notifier.close();
        assertEquals(3, mailer.attempts);
        assertEquals(1, mailer.sent.size());
        assertTrue(catalog.events().stream().anyMatch(e -> e.type().equals("NOTIFICACION_ENVIADA") && e.detail().contains("3")));
    }

    @Test void givesUpAfterTheAttemptsAndNeverPropagatesTheFailure() throws Exception {
        mailer.failFirst = 99;
        var db = database("Laboratorio", "dba@una.cr");
        assertDoesNotThrow(() -> notifier.notifyFinished(execution(db, "FALLIDO", "error", List.of())));
        notifier.close();
        assertEquals(3, mailer.attempts);
        assertTrue(mailer.sent.isEmpty());
        assertTrue(eventTypes().contains("NOTIFICACION_FALLIDA"));
    }

    @Test void subjectCannotCarryHeaderInjection() throws Exception {
        var db = database("Base\r\nBcc: otro@x.com", "dba@una.cr");
        notifier.notifyFinished(execution(db, "FALLIDO", "error", List.of()));
        notifier.close();
        assertEquals(1, mailer.sent.size());
        assertFalse(mailer.sent.getFirst().subject().contains("\n"));
        assertFalse(mailer.sent.getFirst().subject().contains("\r"));
    }
}
