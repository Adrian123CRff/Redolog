package edu.respaldos;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.respaldos.Models.Database;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseModelTest {
    @Test void dbaEmailIsOptional() {
        assertNull(new Database(null, "Base", "c1").dbaEmail());
        assertNull(new Database(null, "Base", "c1", "  ").dbaEmail());
        assertEquals("dba@una.cr", new Database(null, "Base", "c1", " dba@una.cr ").dbaEmail());
    }

    @Test void rejectsMalformedOrOversizedAddresses() {
        for (String bad : new String[] {"sin-arroba", "a@b", "a b@c.com", "dba@una.cr\nBcc: x@y.com", "x".repeat(120) + "@una.cr"})
            assertThrows(IllegalArgumentException.class, () -> new Database(null, "Base", "c1", bad), bad);
    }

    @Test void catalogsSavedBeforeTheFieldExistedStillLoad() throws Exception {
        var reader = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        var db = reader.readValue("{\"id\":\"x\",\"name\":\"Antigua\",\"container\":\"rman-lab\"}", Database.class);
        assertNull(db.dbaEmail());
        assertEquals("Antigua", db.name());
    }
}
