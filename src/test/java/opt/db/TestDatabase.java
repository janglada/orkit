package opt.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a SQLite database (schema + seed data) from a test-resource SQL script
 * into an in-memory database, for use by the database-driven example tests.
 *
 * <p>Each domain (job-shop / scheduling / routing) ships a {@code db/<domain>.sql}
 * resource describing a realistic business schema and seed data. A test loads it
 * with {@link #load(String)}, reads rows back with
 * {@link #select(String, Object...)}, and feeds them into the fluent DSL builders.
 *
 * <p>The database lives entirely in memory on a single connection; it is discarded
 * when {@link #close()} is called. No file is written to disk.
 */
public final class TestDatabase implements AutoCloseable {

    private final Connection connection;

    private TestDatabase(Connection connection) {
        this.connection = connection;
    }

    /**
     * Loads the SQL resource (classpath-relative, e.g. {@code "db/jobshop.sql"})
     * into a fresh in-memory SQLite database and returns a handle to it.
     */
    public static TestDatabase load(String resourcePath) {
        try {
            Class.forName("org.sqlite.JDBC");
            Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
            executeScript(conn, readResource(resourcePath));
            return new TestDatabase(conn);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on the test classpath", e);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite from " + resourcePath, e);
        }
    }

    /** The raw JDBC connection, for tests that need prepared statements or transactions. */
    public Connection connection() {
        return connection;
    }

    /**
     * Runs a parameterized SELECT, returning each row as a case-insensitive
     * column-label → value map. INTEGER columns come back as {@link Long} (SQLite
     * convention); read them with {@link #asLong(Map, String)} to be safe.
     */
    public List<Map<String, Object>> select(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                int cols = rs.getMetaData().getColumnCount();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= cols; c++) {
                        row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    /** Coerces a numeric column value to {@code long} regardless of Integer/Long/Number storage. */
    public static long asLong(Map<String, Object> row, String column) {
        Object v = row.get(column);
        if (v == null) throw new NullPointerException("Missing column: " + column);
        return ((Number) v).longValue();
    }

    /** Returns a nullable numeric column as a {@code Long} ({@code null} when the SQL value is NULL). */
    public static Long asNullableLong(Map<String, Object> row, String column) {
        Object v = row.get(column);
        if (v == null) return null;
        return ((Number) v).longValue();
    }

    /** Coerces a numeric column value to {@code int}. */
    public static int asInt(Map<String, Object> row, String column) {
        return (int) asLong(row, column);
    }

    /** Returns a column value as a non-null string. */
    public static String asString(Map<String, Object> row, String column) {
        Object v = row.get(column);
        if (v == null) throw new NullPointerException("Missing column: " + column);
        return v.toString();
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private static String readResource(String path) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Test resource not found: " + path);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read test resource: " + path, e);
        }
    }

    private static void executeScript(Connection conn, String script) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (String fragment : splitStatements(script)) {
                if (!fragment.isBlank()) {
                    stmt.execute(fragment);
                }
            }
        }
    }

    /** Splits a SQL script into statements on ';', dropping comment lines. */
    private static List<String> splitStatements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : script.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue; // comment line
            cur.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String stmt = cur.toString().trim();
                stmt = stmt.substring(0, stmt.length() - 1).trim();
                out.add(stmt);
                cur.setLength(0);
            }
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().trim());
        return out;
    }
}
