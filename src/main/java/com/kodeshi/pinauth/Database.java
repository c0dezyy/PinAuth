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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * SQLite storage. One connection, used only from one dedicated thread, so every call is
 * serialised (thread-safe) and never blocks the Minecraft main thread. All SQL uses
 * prepared statements.
 */
public final class Database implements AutoCloseable {

    private static final int SCHEMA_VERSION = 1;

    @FunctionalInterface
    private interface SqlTask<T> {
        T run(Connection c) throws SQLException;
    }

    private static final String COLUMNS =
            "uuid, username, pin_hash, status, registered_at, last_login, failed_count, lockout_until";

    private final Logger log;
    private final File file;
    private final ExecutorService exec;
    private Connection conn; // touched only on the executor thread

    public Database(Logger log, File file) {
        this.log = log;
        this.file = file;
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PinAuth-Database");
            t.setDaemon(true);
            return t;
        });
    }

    /** Opens the database and runs migrations; blocks until finished (call from onEnable). */
    public void open() throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        exec.execute(() -> {
            try {
                Class.forName("org.sqlite.JDBC");
                conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA synchronous=FULL");
                    st.execute("PRAGMA busy_timeout=5000");
                }
                migrate();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private void migrate() throws SQLException {
        int version = 0;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            if (rs.next()) {
                version = rs.getInt(1);
            }
        }
        if (version > SCHEMA_VERSION) {
            throw new SQLException("accounts.db was created by a newer PinAuth (schema " + version + ")");
        }
        if (version == SCHEMA_VERSION) {
            return;
        }
        conn.setAutoCommit(false);
        try {
            if (version < 1) {
                try (Statement st = conn.createStatement()) {
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
            }
            // Future migrations go here:  if (version < 2) { ... }
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            }
            conn.commit();
            log.info("Database schema is at version " + SCHEMA_VERSION + ".");
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private <T> CompletableFuture<T> submit(SqlTask<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            exec.execute(() -> {
                try {
                    future.complete(task.run(conn));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    // ------------------------------------------------------------------ queries

    /** Makes sure a row exists for the UUID (status NEEDS_REGISTRATION), refreshes the name, returns it. */
    public CompletableFuture<AccountRecord> loadOrCreate(UUID uuid, String name) {
        return submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO accounts(uuid, username) VALUES(?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE accounts SET username = ? WHERE uuid = ? AND username <> ?")) {
                ps.setString(1, name);
                ps.setString(2, uuid.toString());
                ps.setString(3, name);
                ps.executeUpdate();
            }
            return select(c, uuid).orElseThrow(() -> new SQLException("account row missing"));
        });
    }

    public CompletableFuture<Optional<AccountRecord>> findByUuid(UUID uuid) {
        return submit(c -> select(c, uuid));
    }

    public CompletableFuture<List<AccountRecord>> findByUsername(String name) {
        return submit(c -> {
            List<AccountRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + COLUMNS + " FROM accounts WHERE username = ? COLLATE NOCASE LIMIT 5")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            }
            return out;
        });
    }

    /** Registers a PIN. Returns false (and changes nothing) if the account already has one. */
    public CompletableFuture<Boolean> register(UUID uuid, String name, String hash, long now) {
        return submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO accounts(uuid, username, pin_hash, status, registered_at, last_login, "
                            + "failed_count, lockout_until) VALUES(?, ?, ?, 'REGISTERED', ?, ?, 0, 0) "
                            + "ON CONFLICT(uuid) DO UPDATE SET username = excluded.username, "
                            + "pin_hash = excluded.pin_hash, status = 'REGISTERED', "
                            + "registered_at = excluded.registered_at, last_login = excluded.last_login, "
                            + "failed_count = 0, lockout_until = 0 "
                            + "WHERE accounts.status = 'NEEDS_REGISTRATION'")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, hash);
                ps.setLong(4, now);
                ps.setLong(5, now);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Replaces the hash of an already registered account. */
    public CompletableFuture<Boolean> changePin(UUID uuid, String hash) {
        return submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE accounts SET pin_hash = ?, failed_count = 0, lockout_until = 0 "
                            + "WHERE uuid = ? AND status = 'REGISTERED'")) {
                ps.setString(1, hash);
                ps.setString(2, uuid.toString());
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Admin reset: removes the hash and requires registration. Returns the number of rows changed. */
    public CompletableFuture<Integer> clearPin(UUID uuid) {
        return submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE accounts SET pin_hash = NULL, status = 'NEEDS_REGISTRATION', "
                            + "failed_count = 0, lockout_until = 0 WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                return ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> recordFailure(UUID uuid, int failedCount, long lockoutUntil) {
        return submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE accounts SET failed_count = ?, lockout_until = ? WHERE uuid = ?")) {
                ps.setInt(1, failedCount);
                ps.setLong(2, lockoutUntil);
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> recordSuccess(UUID uuid, String name, long now) {
        return submit(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE accounts SET last_login = ?, failed_count = 0, lockout_until = 0, username = ? "
                            + "WHERE uuid = ?")) {
                ps.setLong(1, now);
                ps.setString(2, name);
                ps.setString(3, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> resetFailures(UUID uuid) {
        return recordFailure(uuid, 0, 0L);
    }

    // ------------------------------------------------------------------ helpers

    private static Optional<AccountRecord> select(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM accounts WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static AccountRecord map(ResultSet rs) throws SQLException {
        String hash = rs.getString(3);
        String rawStatus = rs.getString(4);
        // An unknown status together with a stored hash is treated as REGISTERED (fail safe: never
        // let a damaged row turn into "anyone may register").
        AccountStatus status = "NEEDS_REGISTRATION".equals(rawStatus)
                ? AccountStatus.NEEDS_REGISTRATION
                : (hash != null ? AccountStatus.REGISTERED : AccountStatus.NEEDS_REGISTRATION);
        return new AccountRecord(UUID.fromString(rs.getString(1)), rs.getString(2), hash, status,
                rs.getLong(5), rs.getLong(6), rs.getInt(7), rs.getLong(8));
    }

    @Override
    public void close() {
        exec.shutdown(); // queued writes still run
        try {
            if (!exec.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warning("Database executor did not finish in time.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (SQLException e) {
            log.warning("Could not close the database cleanly: " + e.getMessage());
        }
    }
}
