package edu.respaldos;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.respaldos.Models.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public final class Catalog {
    private final String url;
    private final ObjectMapper json;
    public Catalog(Path directory, ObjectMapper json) throws Exception {
        this.json = json;
        Files.createDirectories(directory);
        url = "jdbc:h2:file:" + directory.resolve("catalogo").toAbsolutePath().toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
        try (var c = connect(); var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS bases (id VARCHAR(36) PRIMARY KEY, payload CLOB NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS strategies (id VARCHAR(36) PRIMARY KEY, payload CLOB NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS executions (id VARCHAR(36) PRIMARY KEY, occurrence VARCHAR(180) UNIQUE, created VARCHAR(50), payload CLOB NOT NULL)");
        }
    }
    private Connection connect() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }
    private <T> List<T> list(String table, Class<T> type) throws Exception {
        try (var c = connect(); var s = c.createStatement(); var r = s.executeQuery("SELECT payload FROM " + table + (table.equals("executions") ? " ORDER BY created DESC LIMIT 200" : " ORDER BY id"))) {
            var values = new ArrayList<T>();
            while (r.next()) values.add(json.readValue(r.getString(1), type));
            return values;
        }
    }
    public List<Database> databases() throws Exception { return list("bases", Database.class); }
    public List<Strategy> strategies() throws Exception { return list("strategies", Strategy.class); }
    public List<Execution> executions() throws Exception { return list("executions", Execution.class); }
    public Database database(String id) throws Exception { return databases().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Base no encontrada.")); }
    public Strategy strategy(String id) throws Exception { return strategies().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Estrategia no encontrada.")); }
    public Execution execution(String id) throws Exception {
        try (var c = connect(); var s = c.prepareStatement("SELECT payload FROM executions WHERE id=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Ejecucion no encontrada.");
                return json.readValue(r.getString(1), Execution.class);
            }
        }
    }
    private void save(String table, String id, Object value) throws Exception {
        try (var c = connect(); var s = c.prepareStatement("MERGE INTO " + table + " (id,payload) KEY(id) VALUES(?,?)")) {
            s.setString(1, id); s.setString(2, json.writeValueAsString(value)); s.executeUpdate();
        }
    }
    public void save(Database value) throws Exception {
        Models.require(databases().stream().noneMatch(d -> d.container().equals(value.container()) && !d.id().equals(value.id())), "Ese contenedor ya esta registrado.");
        save("bases", value.id(), value);
    }
    public void save(Strategy value) throws Exception { database(value.databaseId()); save("strategies", value.id(), value); }
    public void deleteStrategy(String id) throws Exception {
        try (var c = connect(); var s = c.prepareStatement("DELETE FROM strategies WHERE id=?")) { s.setString(1,id); s.executeUpdate(); }
    }
    public boolean insert(Execution value, String occurrence) throws Exception {
        try (var c = connect(); var s = c.prepareStatement("INSERT INTO executions(id,occurrence,created,payload) VALUES(?,?,?,?)")) {
            s.setString(1,value.id()); s.setString(2,occurrence); s.setString(3,value.startedAt()); s.setString(4,json.writeValueAsString(value)); s.executeUpdate(); return true;
        } catch (SQLException e) { if ("23505".equals(e.getSQLState())) return false; throw e; }
    }
    public void update(Execution value) throws Exception {
        try (var c = connect(); var s = c.prepareStatement("UPDATE executions SET payload=? WHERE id=?")) {
            s.setString(1,json.writeValueAsString(value)); s.setString(2,value.id()); s.executeUpdate();
        }
    }
}
