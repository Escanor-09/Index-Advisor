package advisor.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Pooled via HikariCP rather than a fresh DriverManager connection per call
 * (the original design, fine at low request volume but a real bottleneck
 * once the HTTP API — Milestone 14 — can receive concurrent requests: each
 * DB call would otherwise pay a full TCP + auth handshake). Public
 * constructor signature is unchanged so Main.java's CLI usage and
 * AdvisorConfig's Spring bean wiring didn't need to change.
 */
public class PostgresManager implements AutoCloseable {

    private final HikariDataSource dataSource;

    public PostgresManager(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setPoolName("index-advisor-pool");

        this.dataSource = new HikariDataSource(config);
    }

    public boolean testConnection() {
        try (Connection conn = connect()) {
            return conn.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    public String executeScalar(String query) {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: " + query, e);
        }
    }

    /** First column of every row — used for schema lookups (table/column allowlists). */
    public List<String> executeColumn(String query) {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            List<String> values = new ArrayList<>();

            while (rs.next()) {
                values.add(rs.getString(1));
            }

            return values;
        } catch (SQLException e) {
            throw new RuntimeException("Query failed: " + query, e);
        }
    }

    public String explainAnalyze(String query) {
        return executeScalar("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + query);
    }

    /** For DDL (CREATE INDEX, DROP INDEX, ...) — no result set to read. */
    public void executeUpdate(String sql) {
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {

            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Update failed: " + sql, e);
        }
    }

    /**
     * Opens one physical connection for a sequence of statements that must
     * see each other's session-local state — e.g. HypoPG's hypothetical
     * indexes, which live in per-backend memory and are invisible from any
     * other connection, including another one from this same pool. Every
     * other method above independently borrows a (possibly different)
     * connection from the pool per call, which is fine for ordinary
     * queries/DDL (real database state, visible from any connection) but
     * would silently break HypoPG usage if not deliberately pinned like this.
     */
    public ConnectionSession openSession() {
        try {
            return new ConnectionSession(connect());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open session", e);
        }
    }

    public static final class ConnectionSession implements AutoCloseable {

        private final Connection connection;

        private ConnectionSession(Connection connection) {
            this.connection = connection;
        }

        public String executeScalar(String sql) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                return rs.next() ? rs.getString(1) : null;
            } catch (SQLException e) {
                throw new RuntimeException("Query failed: " + sql, e);
            }
        }

        public void executeUpdate(String sql) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(sql);
            } catch (SQLException e) {
                throw new RuntimeException("Update failed: " + sql, e);
            }
        }

        @Override
        public void close() {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to close session", e);
            }
        }
    }

    private Connection connect() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
