package com.kodeshi.pinauth;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** Pre-login lockout / duplicate checks, session start on join, cleanup on quit. */
public final class ConnectionListener implements Listener {
    private final PinAuthPlugin plugin;
    private final AuthManager auth;

    public ConnectionListener(PinAuthPlugin plugin, AuthManager auth) {
        this.plugin = plugin;
        this.auth = auth;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        Settings st = plugin.settings();
        if (!plugin.isReady()) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, st.plain("auth-unavailable-kick"));
            return;
        }
        UUID id = e.getUniqueId();
        // Stops someone connecting with an online player's name (offline mode) just to kick them.
        if (st.blockDuplicateLogin && Bukkit.getPlayer(id) != null) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, st.plain("duplicate-login-kick"));
            return;
        }
        try {
            Account acc = plugin.database().get(id).get(5, TimeUnit.SECONDS);
            long now = System.currentTimeMillis();
            if (acc != null && acc.lockoutUntil() > now) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        st.plain("login-locked-kick", "time", Text.duration(acc.lockoutUntil() - now)));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, st.plain("auth-unavailable-kick"));
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Pre-login account check failed: " + ex.getMessage());
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, st.plain("auth-unavailable-kick"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        auth.beginSession(e.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        auth.handleQuit(e.getPlayer().getUniqueId());
    }
}
