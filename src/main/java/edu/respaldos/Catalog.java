package edu.respaldos;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.respaldos.Models.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Catalogo local (H2) de bases, estrategias, aprobaciones, ejecuciones y bitacora. */
public final class Catalog {
    private final String url;
    private final ObjectMapper json;
    /** Lo guardado por versiones anteriores puede tener campos que ya no existen. */
    private final ObjectMapper reader;

    public Catalog(Path directory, ObjectMapper json) throws Exception {
        this.json = json;
        this.reader = json.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Files.createDirectories(directory);
        url = "jdbc:h2:file:" + directory.resolve("catalogo").toAbsolutePath().toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
        try (var c = connect(); var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS bases (id VARCHAR(36) PRIMARY KEY, payload CLOB NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS strategies (id VARCHAR(36) PRIMARY KEY, payload CLOB NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS executions (id VARCHAR(36) PRIMARY KEY, occurrence VARCHAR(180) UNIQUE, created VARCHAR(50), payload CLOB NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS approvals (id VARCHAR(36) PRIMARY KEY, payload CLOB NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS db_status (id VARCHAR(36) PRIMARY KEY, payload CLOB NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS events (id VARCHAR(36) PRIMARY KEY, created VARCHAR(50), payload CLOB NOT NULL)");
        }
    }

    private Connection connect() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }

    private <T> List<T> list(String sql, Class<T> type, Object... params) throws Exception {
        try (var c = connect(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) s.setObject(i + 1, params[i]);
            try (var r = s.executeQuery()) {
                var values = new ArrayList<T>();
                while (r.next()) values.add(reader.readValue(r.getString(1), type));
                return values;
            }
        }
    }

    public List<Database> databases() throws Exception { return list("SELECT payload FROM bases ORDER BY id", Database.class); }
    public List<Strategy> strategies() throws Exception { return list("SELECT payload FROM strategies ORDER BY id", Strategy.class); }
    public List<Execution> executions() throws Exception { return list("SELECT payload FROM executions ORDER BY created DESC LIMIT 500", Execution.class); }
    public List<Approval> approvals() throws Exception { return list("SELECT payload FROM approvals", Approval.class); }
    public List<DatabaseStatus> statuses() throws Exception { return list("SELECT payload FROM db_status", DatabaseStatus.class); }
    public List<Event> events() throws Exception { return list("SELECT payload FROM events ORDER BY created DESC LIMIT 100", Event.class); }

    public Database database(String id) throws Exception {
        return databases().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Base no encontrada."));
    }

    public Strategy strategy(String id) throws Exception {
        return strategies().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Estrategia no encontrada."));
    }

    public Optional<Approval> approval(String strategyId) throws Exception {
        return list("SELECT payload FROM approvals WHERE id=?", Approval.class, strategyId).stream().findFirst();
    }

    public Optional<DatabaseStatus> status(String databaseId) throws Exception {
        return list("SELECT payload FROM db_status WHERE id=?", DatabaseStatus.class, databaseId).stream().findFirst();
    }

    public Execution execution(String id) throws Exception {
        return list("SELECT payload FROM executions WHERE id=?", Execution.class, id).stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Ejecucion no encontrada."));
    }

    /** Claves de ocurrencia registradas para una estrategia (ejecutadas u omitidas). */
    public Set<String> occurrences(String strategyId) throws Exception {
        try (var c = connect(); var s = c.prepareStatement("SELECT occurrence FROM executions WHERE occurrence LIKE ?")) {
            s.setString(1, strategyId + "|%");
            try (var r = s.executeQuery()) {
                var values = new HashSet<String>();
                while (r.next()) values.add(r.getString(1));
                return values;
            }
        }
    }

    private void save(String table, String id, Object value) throws Exception {
        try (var c = connect(); var s = c.prepareStatement("MERGE INTO " + table + " (id,payload) KEY(id) VALUES(?,?)")) {
            s.setString(1, id); s.setString(2, json.writeValueAsString(value)); s.executeUpdate();
        }
    }

    private void delete(String table, String id) throws Exception {
        try (var c = connect(); var s = c.prepareStatement("DELETE FROM " + table + " WHERE id=?")) { s.setString(1, id); s.executeUpdate(); }
    }

    public void save(Database value) throws Exception {
        Models.require(databases().stream().noneMatch(d -> d.container().equals(value.container()) && !d.id().equals(value.id())), "Ese contenedor ya esta registrado.");
        save("bases", value.id(), value);
    }

    public void save(Strategy value) throws Exception { database(value.databaseId()); save("strategies", value.id(), value); }
    public void save(Approval value) throws Exception { save("approvals", value.strategyId(), value); }
    public void save(DatabaseStatus value) throws Exception { save("db_status", value.databaseId(), value); }

    public void deleteStrategy(String id) throws Exception { delete("strategies", id); delete("approvals", id); }

    public void log(Event value) throws Exception {
        try (var c = connect(); var s = c.prepareStatement("INSERT INTO events(id,created,payload) VALUES(?,?,?)")) {
            s.setString(1, value.id()); s.setString(2, value.at()); s.setString(3, json.writeValueAsString(value)); s.executeUpdate();
        }
    }

    public boolean insert(Execution value, String occurrence) throws Exception {
        try (var c = connect(); var s = c.prepareStatement("INSERT INTO executions(id,occurrence,created,payload) VALUES(?,?,?,?)")) {
            s.setString(1, value.id()); s.setString(2, occurrence); s.setString(3, value.startedAt()); s.setString(4, json.writeValueAsString(value)); s.executeUpdate(); return true;
        } catch (SQLException e) { if ("23505".equals(e.getSQLState())) return false; throw e; }
    }

    public void update(Execution value) throws Exception {
        try (var c = connect(); var s = c.prepareStatement("UPDATE executions SET payload=? WHERE id=?")) {
            s.setString(1, json.writeValueAsString(value)); s.setString(2, value.id()); s.executeUpdate();
        }
    }
}
