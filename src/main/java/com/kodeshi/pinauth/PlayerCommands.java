package com.kodeshi.pinauth;

import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** /pin (reopen the window) and /changepin (needs the old PIN). */
public final class PlayerCommands implements CommandExecutor, TabCompleter {

    private final AuthManager auth;

    public PlayerCommands(AuthManager auth) {
        this.auth = auth;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(auth.settings().msg("players-only"));
            return true;
        }
        switch (cmd.getName().toLowerCase()) {
            case "pin" -> auth.reopen(p);
            case "changepin" -> {
                if (!p.hasPermission("pinauth.change")) {
                    p.sendMessage(auth.settings().msg("no-permission"));
                } else {
                    auth.startChange(p);
                }
            }
            default -> { }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        return List.of();
    }
}
