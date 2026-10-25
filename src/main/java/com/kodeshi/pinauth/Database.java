package com.kodeshi.pinauth;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite storage. One connection, one dedicated thread: every statement runs on that thread,
 * so access is thread-safe and never blocks the Minecraft main thread.
 * All SQL uses prepared statements.
 */
public final class Database {
    private static final int SCHEMA_VERSION = 2;
    private static final String COLUMNS =
            "uuid, username, pin_hash, status, registered_at, last_login, failed_count, lockout_until, edition";

    public record FailureResult(int failedCount, boolean locked, long lockoutUntil) {
    }

    @FunctionalInterface
    private interface SqlCall<T> {
        T call() throws SQLException;
    }

    private final File file;
    private final Logger log;
    private final ExecutorService executor;
    private Connection connection; // touched only from the executor thread (and after termination in close())

    public Database(File file, Logger log) {
        this.file = file;
        this.log = log;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PinAuth-DB");
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------------ lifecycle

    public void init() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver (org.sqlite.JDBC) not found on this server", e);
        }
        try {
            executor.submit(() -> {
                open();
                migrate();
                return null;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while initialising the database", e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof SQLException se) throw se;
            throw new SQLException("Database initialisation failed: " + c, c);
        }
    }

    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // nothing sensible to do while shutting down
            }
        }
    }

    private void open() throws SQLException {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // replacing a broken connection
            }
        }
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA foreign_keys=ON");
        }
    }

    private void ensureConnection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            log.warning("Re-opening the SQLite connection.");
            open();
        }
    }

    // ------------------------------------------------------------------ migrations

    private void migrate() throws SQLException {
        int version;
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            version = rs.next() ? rs.getInt(1) : 0;
        }
        if (version > SCHEMA_VERSION) {
            throw new SQLException("Database schema version " + version
                    + " is newer than this plugin supports (" + SCHEMA_VERSION + ")");
        }
        while (version < SCHEMA_VERSION) {
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                switch (version) {
                    case 0 -> {
                        st.execute("CREATE TABLE IF NOT EXISTS accounts ("
                                + "uuid TEXT PRIMARY KEY NOT NULL,"
                                + "username TEXT NOT NULL,"
                                + "pin_hash TEXT,"
                                + "status TEXT NOT NULL DEFAULT 'NEEDS_REGISTRATION',"
                                + "registered_at INTEGER NOT NULL DEFAULT 0,"
                                + "last_login INTEGER NOT NULL DEFAULT 0,"
                                + "failed_count INTEGER NOT NULL DEFAULT 0,"
                                + "lockout_until INTEGER NOT NULL DEFAULT 0)");
                        st.execute("CREATE INDEX IF NOT EXISTS idx_accounts_username "
                                + "ON accounts(username COLLATE NOCASE)");
                    }
                    case 1 -> st.execute("ALTER TABLE accounts ADD COLUMN edition TEXT NOT NULL DEFAULT 'JAVA'");
                    default -> throw new SQLException("No migration path from schema version " + version);
                }
                version++;
                st.execute("PRAGMA user_version = " + version);
                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // original error is more useful
                }
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    // ------------------------------------------------------------------ execution helpers

    private <T> CompletableFuture<T> submit(SqlCall<T> call) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    ensureConnection();
                    future.complete(call.call());
                } catch (Throwable t) {
                    log.log(Level.SEVERE, "Database operation failed: " + t.getMessage());
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /** Runs the call inside a transaction (commit on success, rollback on failure). */
    private <T> CompletableFuture<T> write(SqlCall<T> call) {
        return submit(() -> {
            connection.setAutoCommit(false);
            try {
                T result = call.call();
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // keep the original exception
                }
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    private Account map(ResultSet rs) throws SQLException {
        return new Account(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), rs.getLong(6), rs.getInt(7), rs.getLong(8), rs.getString(9));
    }

    private Account select(UUID id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM accounts WHERE uuid = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    // ------------------------------------------------------------------ queries

    public CompletableFuture<Account> get(UUID id) {
        return submit(() -> select(id));
    }

    public CompletableFuture<List<Account>> findByUsername(String name) {
        return submit(() -> {
            List<Account> out = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT " + COLUMNS
                    + " FROM accounts WHERE username = ? COLLATE NOCASE ORDER BY last_login DESC LIMIT 10")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(map(rs));
                }
            }
            return out;
        });
    }

    /** Loads the account for this UUID, creating a NEEDS_REGISTRATION row on first join. */
    public CompletableFuture<Account> loadOrCreate(UUID id, String username, String edition) {
        return write(() -> {
            Account acc = select(id);
            if (acc == null) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO accounts (uuid, username, status, edition) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, id.toString());
                    ps.setString(2, username);
                    ps.setString(3, Account.STATUS_NEEDS_REGISTRATION);
                    ps.setString(4, edition);
                    ps.executeUpdate();
                }
                return select(id);
            }
            if (!acc.username().equals(username) || !edition.equals(acc.edition())) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE accounts SET username = ?, edition = ? WHERE uuid = ?")) {
                    ps.setString(1, username);
                    ps.setString(2, edition);
                    ps.setString(3, id.toString());
                    ps.executeUpdate();
                }
                return select(id);
            }
            return acc;
        });
    }

    /** First-time PIN registration. Returns false if the account already has a PIN. */
    public CompletableFuture<Boolean> registerPin(UUID id, String hash, long now) {
        return write(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE accounts SET pin_hash = ?, status = ?, registered_at = ?, last_login = ?, "
                            + "failed_count = 0, lockout_until = 0 WHERE uuid = ? AND status = ?")) {
                ps.setString(1, hash);
                ps.setString(2, Account.STATUS_REGISTERED);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.setString(5, id.toString());
                ps.setString(6, Account.STATUS_NEEDS_REGISTRATION);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Replaces the PIN of an already registered account. */
    public CompletableFuture<Boolean> changePin(UUID id, String hash) {
        return write(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE accounts SET pin_hash = ?, failed_count = 0, lockout_until = 0 "
                            + "WHERE uuid = ? AND status = ?")) {
                ps.setString(1, hash);
                ps.setString(2, id.toString());
                ps.setString(3, Account.STATUS_REGISTERED);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Admin reset: removes the hash and marks the account NEEDS_REGISTRATION. False = no such account. */
    public CompletableFuture<Boolean> clearPin(UUID id) {
        return write(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE accounts SET pin_hash = NULL, status = ?, failed_count = 0, lockout_until = 0 "
                            + "WHERE uuid = ?")) {
                ps.setString(1, Account.STATUS_NEEDS_REGISTRATION);
                ps.setString(2, id.toString());
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Resets the failure counter. False if the account has no PIN any more. */
    public CompletableFuture<Boolean> recordSuccess(UUID id, long now, boolean updateLastLogin) {
        return write(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE accounts SET failed_count = 0, lockout_until = 0, "
                            + "last_login = CASE WHEN ? = 1 THEN ? ELSE last_login END "
                            + "WHERE uuid = ? AND status = ? AND pin_hash IS NOT NULL")) {
                ps.setInt(1, updateLastLogin ? 1 : 0);
                ps.setLong(2, now);
                ps.setString(3, id.toString());
                ps.setString(4, Account.STATUS_REGISTERED);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /**
     * Persistently counts a wrong PIN. When the counter reaches {@code max} the account is locked for
     * {@code lockoutMillis} and the counter restarts from zero for the next window.
     */
    public CompletableFuture<FailureResult> recordFailure(UUID id, int max, long lockoutMillis, long now) {
        return write(() -> {
            int current = 0;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT failed_count FROM accounts WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) current = rs.getInt(1);
                }
            }
            int next = current + 1;
            boolean locked = next >= max;
            long until = locked && lockoutMillis > 0 ? now + lockoutMillis : 0L;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE accounts SET failed_count = ?, lockout_until = ? WHERE uuid = ?")) {
                ps.setInt(1, locked ? 0 : next);
                ps.setLong(2, until);
                ps.setString(3, id.toString());
                ps.executeUpdate();
            }
            return new FailureResult(next, locked, until);
        });
    }
}
