package com.kodeshi.pinauth;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** All commands. Permissions are re-checked here, on the server, in addition to plugin.yml. */
public final class PinCommands implements TabExecutor {
    private static final String ADMIN = "pinauth.admin";
    private static final String RELOAD = "pinauth.reload";

    private record Target(UUID uuid, String name) {
    }

    private final PinAuthPlugin plugin;

    public PinCommands(PinAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "pinreset", "clearpin" -> handleReset(sender, label, args);
            case "pinstatus" -> handleStatus(sender, label, args);
            case "pinauth" -> handlePinAuth(sender, args);
            case "pinlogin" -> handleLogin(sender);
            case "changepin" -> handleChange(sender);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ admin: reset / clear

    private void handleReset(CommandSender sender, String label, String[] args) {
        Settings st = plugin.settings();
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(st.message("admin-no-permission"));
            return;
        }
        if (args.length != 1) {
            sender.sendMessage(st.message("admin-usage", "label", label));
            return;
        }
        resolve(sender, args[0], target -> {
            Database db = plugin.database();
            db.clearPin(target.uuid()).whenComplete((found, err) -> plugin.runMain(() -> {
                Settings now = plugin.settings();
                if (err != null) {
                    sender.sendMessage(now.message("admin-error"));
                    return;
                }
                if (found == null || !found) {
                    sender.sendMessage(now.message("admin-player-not-found", "player", target.name()));
                    return;
                }
                Player online = Bukkit.getPlayer(target.uuid());
                if (online != null) plugin.auth().revoke(online); // revoke session -> registration GUI
                sender.sendMessage(now.message("admin-reset-success", "player", target.name()));
                // Audit log: who/what only. Never PINs or hashes.
                plugin.getLogger().info("Admin " + sender.getName() + " reset the PIN of " + target.name()
                        + " (" + target.uuid() + ")" + (online != null ? " [online, session revoked]" : " [offline]"));
            }));
        });
    }

    // ------------------------------------------------------------------ admin: status

    private void handleStatus(CommandSender sender, String label, String[] args) {
        Settings st = plugin.settings();
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(st.message("admin-no-permission"));
            return;
        }
        if (args.length != 1) {
            sender.sendMessage(st.message("admin-usage", "label", label));
            return;
        }
        resolve(sender, args[0], target -> plugin.database().get(target.uuid())
                .whenComplete((acc, err) -> plugin.runMain(() -> {
                    Settings now = plugin.settings();
                    if (err != null) {
                        sender.sendMessage(now.message("admin-error"));
                        return;
                    }
                    if (acc == null) {
                        sender.sendMessage(now.message("admin-player-not-found", "player", target.name()));
                        return;
                    }
                    Player online = Bukkit.getPlayer(acc.uuid());
                    String session;
                    if (online == null) session = "offline";
                    else session = plugin.auth().isAuthenticated(online) ? "online, authenticated"
                            : "online, NOT authenticated";
                    long time = System.currentTimeMillis();
                    String lock = acc.lockoutUntil() > time
                            ? "locked for " + Text.duration(acc.lockoutUntil() - time) : "none";
                    sender.sendMessage(now.message("status-format",
                            "player", acc.username(),
                            "uuid", acc.uuid().toString(),
                            "pin", acc.hasPin() ? "&aregistered" : "&cnot registered",
                            "status", acc.status(),
                            "edition", acc.edition() == null ? "JAVA" : acc.edition(),
                            "session", session,
                            "lock", lock,
                            "failed", String.valueOf(acc.failedCount())));
                })));
    }

    // ------------------------------------------------------------------ /pinauth

    private void handlePinAuth(CommandSender sender, String[] args) {
        Settings st = plugin.settings();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission(RELOAD)) {
                    sender.sendMessage(st.message("admin-no-permission"));
                    return;
                }
                try {
                    plugin.reloadSettings();
                    sender.sendMessage(plugin.settings().message("reload-success"));
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Configuration reload failed: " + e.getMessage());
                    sender.sendMessage(plugin.settings().message("reload-failed"));
                }
            }
            case "help" -> {
                if (!sender.hasPermission(ADMIN) && !sender.hasPermission(RELOAD)) {
                    sender.sendMessage(st.message("admin-no-permission"));
                    return;
                }
                sender.sendMessage(st.plain("help"));
            }
            default -> sender.sendMessage(st.message("admin-usage", "label", "pinauth help"));
        }
    }

    // ------------------------------------------------------------------ player commands

    private void handleLogin(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.settings().message("player-only"));
            return;
        }
        plugin.auth().reopen(player);
    }

    private void handleChange(CommandSender sender) {
        Settings st = plugin.settings();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(st.message("player-only"));
            return;
        }
        if (!player.hasPermission("pinauth.changepin")) {
            player.sendMessage(st.message("admin-no-permission"));
            return;
        }
        if (!plugin.auth().isAuthenticated(player)) {
            player.sendMessage(st.message("command-blocked"));
            return;
        }
        plugin.auth().startChange(player);
    }

    // ------------------------------------------------------------------ target resolution

    /** Online name -> UUID string -> stored username (case-insensitive). Never trusts a bare name for ambiguity. */
    private void resolve(CommandSender sender, String arg, Consumer<Target> callback) {
        Settings st = plugin.settings();
        Player online = Bukkit.getPlayerExact(arg);
        if (online != null) {
            callback.accept(new Target(online.getUniqueId(), online.getName()));
            return;
        }
        Database db = plugin.database();
        if (db == null) {
            sender.sendMessage(st.message("admin-error"));
            return;
        }
        UUID parsed = parseUuid(arg);
        CompletableFuture<List<Account>> lookup;
        if (parsed != null) {
            lookup = db.get(parsed).thenApply(a -> a == null ? List.<Account>of() : List.of(a));
        } else {
            lookup = db.findByUsername(arg);
        }
        lookup.whenComplete((list, err) -> plugin.runMain(() -> {
            Settings now = plugin.settings();
            if (err != null) {
                sender.sendMessage(now.message("admin-error"));
            } else if (list == null || list.isEmpty()) {
                sender.sendMessage(now.message("admin-player-not-found", "player", arg));
            } else if (list.size() > 1) {
                StringBuilder sb = new StringBuilder();
                for (Account a : list) {
                    sb.append("&7- &f").append(a.username()).append(" &8(").append(a.uuid()).append(")\n");
                }
                sender.sendMessage(now.message("admin-multiple", "list", sb.toString().trim()));
            } else {
                Account a = list.get(0);
                callback.accept(new Target(a.uuid(), a.username()));
            }
        }));
    }

    private static UUID parseUuid(String s) {
        if (s.length() != 36) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "pinreset", "clearpin", "pinstatus" -> {
                if (!sender.hasPermission(ADMIN)) return List.of();
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) names.add(p.getName());
                }
                names.sort(String.CASE_INSENSITIVE_ORDER);
                return names;
            }
            case "pinauth" -> {
                List<String> opts = new ArrayList<>();
                if (sender.hasPermission(ADMIN) || sender.hasPermission(RELOAD)) opts.add("help");
                if (sender.hasPermission(RELOAD)) opts.add("reload");
                opts.removeIf(o -> !o.startsWith(prefix));
                return opts;
            }
            default -> {
                return List.of();
            }
        }
    }
}
