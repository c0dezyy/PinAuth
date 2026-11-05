package com.kodeshi.pinauth;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** /pinreset, /clearpin, /pinstatus, /pinauth reload|help. Permissions are re-checked here, server-side. */
public final class AdminCommands implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private record Target(UUID uuid, String name) {
    }

    private final PinAuthPlugin plugin;
    private final AuthManager auth;
    private final Database db;

    public AdminCommands(PinAuthPlugin plugin, AuthManager auth, Database db) {
        this.plugin = plugin;
        this.auth = auth;
        this.db = db;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Settings st = plugin.settings();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "pinreset", "clearpin" -> {
                if (!sender.hasPermission("pinauth.admin")) {
                    sender.sendMessage(st.msg("no-permission"));
                } else if (args.length != 1) {
                    sender.sendMessage(st.msg("usage", "usage", "/" + name + " <player>"));
                } else {
                    resolve(sender, args[0], t -> auth.adminReset(sender, t.uuid(), t.name()));
                }
            }
            case "pinstatus" -> {
                if (!sender.hasPermission("pinauth.admin")) {
                    sender.sendMessage(st.msg("no-permission"));
                } else if (args.length != 1) {
                    sender.sendMessage(st.msg("usage", "usage", "/pinstatus <player>"));
                } else {
                    resolve(sender, args[0], t -> showStatus(sender, t));
                }
            }
            case "pinauth" -> {
                String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
                if (sub.equals("reload")) {
                    if (!sender.hasPermission("pinauth.reload")) {
                        sender.sendMessage(st.msg("no-permission"));
                    } else {
                        reload(sender);
                    }
                } else if (sub.equals("help")) {
                    if (!sender.hasPermission("pinauth.admin")) {
                        sender.sendMessage(st.msg("no-permission"));
                    } else {
                        help(sender);
                    }
                } else {
                    sender.sendMessage(st.msg("usage", "usage", "/pinauth <reload|help>"));
                }
            }
            default -> { }
        }
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text("PinAuth commands", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/pinreset <player> - clear a PIN, force re-registration", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/clearpin <player> - same as /pinreset", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/pinstatus <player> - show registration status", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/pinauth reload - reload config.yml (pinauth.reload)", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/changepin - players change their own PIN (needs the old PIN)", NamedTextColor.GRAY));
    }

    private void reload(CommandSender sender) {
        List<String> warnings = plugin.reloadSettings();
        Settings st = plugin.settings();
        if (warnings == null) {
            sender.sendMessage(st.msg("reload-failed"));
            return;
        }
        sender.sendMessage(st.msg("reload-success"));
        for (String w : warnings) {
            sender.sendMessage(Component.text("- " + w, NamedTextColor.YELLOW));
        }
    }

    /** Finds the account for an online name, an offline name stored in the database, or a UUID. */
    private void resolve(CommandSender sender, String arg, Consumer<Target> callback) {
        Player online = Bukkit.getPlayerExact(arg);
        if (online != null) {
            callback.accept(new Target(online.getUniqueId(), online.getName()));
            return;
        }
        UUID parsed = parseUuid(arg);
        CompletableFuture<List<AccountRecord>> lookup;
        if (parsed != null) {
            lookup = db.findByUuid(parsed).thenApply(opt -> {
                List<AccountRecord> list = new ArrayList<>();
                opt.ifPresent(list::add);
                return list;
            });
        } else {
            lookup = db.findByUsername(arg);
        }
        lookup.whenComplete((list, err) -> plugin.runSync(() -> {
            Settings st = plugin.settings();
            if (err != null) {
                plugin.getLogger().severe("Database error during player lookup: " + err);
                sender.sendMessage(st.msg("admin-db-error"));
            } else if (list.isEmpty()) {
                sender.sendMessage(st.msg("admin-not-found", "name", arg));
            } else if (list.size() > 1) {
                sender.sendMessage(st.msg("admin-ambiguous", "name", arg));
            } else {
                AccountRecord r = list.get(0);
                callback.accept(new Target(r.uuid(), r.username()));
            }
        }));
    }

    private void showStatus(CommandSender sender, Target t) {
        db.findByUuid(t.uuid()).whenComplete((opt, err) -> plugin.runSync(() -> {
            Settings st = plugin.settings();
            if (err != null) {
                plugin.getLogger().severe("Database error during status lookup: " + err);
                sender.sendMessage(st.msg("admin-db-error"));
                return;
            }
            if (opt.isEmpty()) {
                sender.sendMessage(st.msg("admin-not-found", "name", t.name()));
                return;
            }
            AccountRecord r = opt.get();
            Session s = auth.session(t.uuid());
            String session = s == null ? st.raw("status-offline")
                    : (s.authenticated ? st.raw("status-online-auth") : st.raw("status-online-wait"));
            long now = System.currentTimeMillis();
            String lock = r.lockoutUntil() > now ? TIME.format(java.time.Instant.ofEpochMilli(r.lockoutUntil()))
                    : st.raw("status-none");
            String last = r.lastLogin() == 0 ? st.raw("status-never")
                    : TIME.format(java.time.Instant.ofEpochMilli(r.lastLogin()));
            sender.sendMessage(st.msg("status-header", "player", r.username()));
            sender.sendMessage(st.msg("status-pin", "value",
                    r.hasPin() ? st.raw("status-registered") : st.raw("status-unregistered")));
            sender.sendMessage(st.msg("status-online", "value", session));
            sender.sendMessage(st.msg("status-login", "value", last));
            sender.sendMessage(st.msg("status-lock", "value", lock));
        }));
    }

    private static UUID parseUuid(String s) {
        if (s.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (args.length != 1) {
            return out;
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        if (name.equals("pinauth")) {
            if (sender.hasPermission("pinauth.reload") && "reload".startsWith(prefix)) {
                out.add("reload");
            }
            if (sender.hasPermission("pinauth.admin") && "help".startsWith(prefix)) {
                out.add("help");
            }
        } else if (sender.hasPermission("pinauth.admin")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
