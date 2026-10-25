package com.kodeshi.pinauth;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * CrossplayPinAuth - 6-digit PIN authentication with an inventory keypad for Java and Bedrock players.
 * Fails closed: if storage cannot be initialised, every connection is refused until it is fixed.
 */
public final class PinAuthPlugin extends JavaPlugin {
    private volatile Settings settings;
    private volatile Database database;
    private volatile boolean ready;
    private PinHasher hasher;
    private AuthManager auth;
    private FloodgateHook floodgate;
    private ExecutorService hashPool;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig(), getLogger());
        floodgate = FloodgateHook.create(this);
        hashPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "PinAuth-Hash");
            t.setDaemon(true);
            return t;
        });

        ready = initStorage();
        if (!ready) {
            getLogger().severe("Authentication storage is NOT available. All connections will be refused "
                    + "until the problem above is fixed and the server is restarted.");
        }

        auth = new AuthManager(this);
        Bukkit.getPluginManager().registerEvents(new ConnectionListener(this, auth), this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(auth), this);
        Bukkit.getPluginManager().registerEvents(new ProtectionListener(this, auth), this);

        PinCommands commands = new PinCommands(this);
        for (String name : List.of("pinreset", "clearpin", "pinstatus", "pinauth", "pinlogin", "changepin")) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(commands);
                pc.setTabCompleter(commands);
            }
        }

        // Players already online (e.g. after a plugin reload) must authenticate too.
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) auth.beginSession(p, true);
        });

        getLogger().info("CrossplayPinAuth enabled (PIN length " + settings.lengthDescription()
                + ", max attempts " + settings.maxLoginAttempts + ").");
    }

    @Override
    public void onDisable() {
        if (auth != null) auth.shutdown();
        if (database != null) database.close();
        if (hashPool != null) hashPool.shutdownNow();
    }

    private boolean initStorage() {
        byte[] secret;
        try {
            secret = loadOrCreateSecret();
        } catch (IOException | IllegalArgumentException e) {
            getLogger().log(Level.SEVERE, "Could not load secret.key: " + e.getMessage());
            return false;
        }
        hasher = new PinHasher(secret, settings.hashIterations);
        try {
            Database db = new Database(new File(getDataFolder(), settings.databaseFile), getLogger());
            db.init();
            database = db;
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Database initialisation failed: " + e.getMessage(), e);
            return false;
        }
    }

    /** Server-side secret mixed into every PIN hash. Keep it out of any shared copy of the database. */
    private byte[] loadOrCreateSecret() throws IOException {
        File dir = getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create plugin folder");
        File file = new File(dir, "secret.key");
        if (!file.exists()) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            Files.writeString(file.toPath(), Base64.getEncoder().encodeToString(bytes), StandardCharsets.US_ASCII);
            try {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // non-POSIX file system
            }
            getLogger().info("Generated a new secret.key. Back it up together with the database: "
                    + "PINs cannot be verified without it.");
            if (new File(dir, settings.databaseFile).exists()) {
                getLogger().warning("A database already exists but secret.key was missing. Existing PINs can no "
                        + "longer be verified; restore the old secret.key or use /pinreset for affected players.");
            }
        }
        byte[] decoded = Base64.getDecoder().decode(Files.readString(file.toPath(), StandardCharsets.US_ASCII).trim());
        if (decoded.length < 16) throw new IllegalArgumentException("secret.key is too short");
        return decoded;
    }

    /** Re-reads config.yml. Invalid values fall back to defaults; the database is not reopened. */
    public void reloadSettings() {
        reloadConfig();
        Settings fresh = new Settings(getConfig(), getLogger());
        this.settings = fresh;
        if (hasher != null) hasher.setIterations(fresh.hashIterations);
        if (auth != null) auth.refreshAll();
    }

    /** Runs on the main thread; silently ignored while the plugin is shutting down. */
    public void runMain(Runnable task) {
        if (!isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(this, task);
        } catch (IllegalPluginAccessException ignored) {
            // plugin disabled in the meantime
        }
    }

    public Settings settings() {
        return settings;
    }

    public Database database() {
        return database;
    }

    public boolean isReady() {
        return ready;
    }

    public PinHasher hasher() {
        return hasher;
    }

    public AuthManager auth() {
        return auth;
    }

    public FloodgateHook floodgate() {
        return floodgate;
    }

    public ExecutorService hashPool() {
        return hashPool;
    }
}
