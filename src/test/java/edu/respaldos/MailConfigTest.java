package edu.respaldos;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MailConfigTest {
    @Test void withoutHostThereIsNoMail() {
        assertTrue(MailConfig.from(Map.<String, String>of()::get).isEmpty());
        assertTrue(MailConfig.from(Map.of("GESTOR_SMTP_HOST", "  ")::get).isEmpty());
    }

    @Test void appliesDefaultsAndUsesTheUserAsSenderWhenThereIsNoFrom() {
        var c = MailConfig.from(Map.of("GESTOR_SMTP_HOST", "smtp.gmail.com", "GESTOR_SMTP_USER", "robot@una.cr", "GESTOR_SMTP_PASSWORD", "x")::get).orElseThrow();
        assertEquals(587, c.port());
        assertTrue(c.startTls());
        assertEquals("robot@una.cr", c.from());
    }

    @Test void readsExplicitValues() {
        var c = MailConfig.from(Map.of("GESTOR_SMTP_HOST", "localhost", "GESTOR_SMTP_PORT", "2525", "GESTOR_SMTP_STARTTLS", "false", "GESTOR_SMTP_FROM", "a@b.cr")::get).orElseThrow();
        assertEquals(2525, c.port());
        assertFalse(c.startTls());
        assertNull(c.user());
    }

    @Test void aHostWithoutAnySenderIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MailConfig.from(Map.of("GESTOR_SMTP_HOST", "localhost")::get));
    }
}
