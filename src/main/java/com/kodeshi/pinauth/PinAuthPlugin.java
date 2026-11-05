package com.kodeshi.pinauth;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PinAuthPlugin extends JavaPlugin {

    private volatile Settings settings;
    private Database database;
    private AuthManager auth;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            this.settings = loadSettings();
        } catch (Exception e) {
            failClosed("config.yml could not be loaded: " + e.getMessage());
            return;
        }
        for (String w : settings.warnings) {
            getLogger().warning("config.yml: " + w);
        }

        PinHasher hasher;
        try {
            File dir = getDataFolder();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IllegalStateException("cannot create " + dir);
            }
            database = new Database(getLogger(), new File(dir, "accounts.db"));
            database.open();
            hasher = new PinHasher(PinHasher.loadOrCreatePepper(new File(dir, "pepper.key")));
        } catch (Exception e) {
            failClosed("startup failed: " + e);
            return;
        }

        FloodgateHook floodgate = new FloodgateHook(getLogger());
        if (!floodgate.isAvailable() && settings.allowBedrock) {
            getLogger().warning("Floodgate was not found. Bedrock players are detected only by the Floodgate UUID "
                    + "convention. Install Floodgate (and Geyser) to support Bedrock Edition properly, or set "
                    + "allow-bedrock-players: false.");
        }

        auth = new AuthManager(this, database, hasher, floodgate);
        Bukkit.getPluginManager().registerEvents(new ConnectionListener(auth), this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(this, auth), this);
        Bukkit.getPluginManager().registerEvents(new ProtectionListener(auth), this);

        AdminCommands admin = new AdminCommands(this, auth, database);
        bind("pinreset", admin);
        bind("clearpin", admin);
        bind("pinstatus", admin);
        bind("pinauth", admin);
        PlayerCommands players = new PlayerCommands(auth);
        bind("pin", players);
        bind("changepin", players);

        for (Player p : Bukkit.getOnlinePlayers()) {
            auth.attachExisting(p);
        }
        getLogger().info("PinAuth enabled.");
    }

    @Override
    public void onDisable() {
        if (auth != null) {
            auth.shutdown();
        }
        if (database != null) {
            database.close();
        }
    }

    public Settings settings() {
        return settings;
    }

    /** Re-reads config.yml. Returns the warnings, or null if the file is invalid (old settings stay active). */
    public List<String> reloadSettings() {
        try {
            Settings fresh = loadSettings();
            this.settings = fresh;
            return fresh.warnings;
        } catch (Exception e) {
            getLogger().severe("Configuration reload failed, keeping previous settings: " + e.getMessage());
            return null;
        }
    }

    private Settings loadSettings() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new File(getDataFolder(), "config.yml")); // throws on invalid YAML
        try (InputStream in = getResource("config.yml")) {
            if (in != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        }
        return new Settings(yaml);
    }

    public void runSync(Runnable r) {
        if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, r);
        }
    }

    public void runAsync(Runnable r) {
        if (isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, r);
        }
    }

    private void bind(String name, CommandExecutor executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().severe("Command '" + name + "' is missing from plugin.yml");
            return;
        }
        cmd.setExecutor(executor);
        if (executor instanceof TabCompleter tc) {
            cmd.setTabCompleter(tc);
        }
    }

    /** Without a working database nobody may be let in unauthenticated, so refuse every login. */
    private void failClosed(String reason) {
        getLogger().severe(reason);
        getLogger().severe("PinAuth cannot start: ALL LOGINS WILL BE REFUSED until this is fixed.");
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPreLogin(AsyncPlayerPreLoginEvent e) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text("Authentication is unavailable. Please contact an administrator."));
            }
        }, this);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.kick(Component.text("Authentication is unavailable. Please contact an administrator."));
        }
        if (database != null) {
            database.close();
            database = null;
        }
    }
}
