package com.kodeshi.pinauth;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Optional Floodgate integration, accessed reflectively so the plugin loads (and warns clearly)
 * when Floodgate is missing. Authentication records are always keyed by UUID, never by username.
 */
public final class FloodgateHook {
    private final Object api;
    private final Method isFloodgatePlayer;

    private FloodgateHook(Object api, Method isFloodgatePlayer) {
        this.api = api;
        this.isFloodgatePlayer = isFloodgatePlayer;
    }

    public static FloodgateHook create(PinAuthPlugin plugin) {
        Plugin fg = Bukkit.getPluginManager().getPlugin("floodgate");
        if (fg == null || !fg.isEnabled()) {
            plugin.getLogger().warning("Floodgate was not found. Bedrock players cannot be identified through the "
                    + "Floodgate API (a UUID-based fallback is used). Install Floodgate if you use GeyserMC.");
            return new FloodgateHook(null, null);
        }
        try {
            Class<?> cls = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true,
                    fg.getClass().getClassLoader());
            Object api = cls.getMethod("getInstance").invoke(null);
            Method m = cls.getMethod("isFloodgatePlayer", UUID.class);
            plugin.getLogger().info("Floodgate detected; Bedrock players are identified through its API.");
            return new FloodgateHook(api, m);
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("Floodgate is installed but its API could not be used (" + e
                    + "). Falling back to the UUID heuristic.");
            return new FloodgateHook(null, null);
        }
    }

    public boolean isAvailable() {
        return api != null;
    }

    public boolean isBedrock(Player player) {
        UUID id = player.getUniqueId();
        if (api != null) {
            try {
                return (Boolean) isFloodgatePlayer.invoke(api, id);
            } catch (ReflectiveOperationException | ClassCastException ignored) {
                // fall through to heuristic
            }
        }
        // Floodgate-generated UUIDs have a zero most-significant half.
        return id.getMostSignificantBits() == 0L;
    }
}
