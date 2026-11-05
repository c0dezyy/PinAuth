package com.kodeshi.pinauth;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Optional Floodgate integration done by reflection so PinAuth has no hard dependency.
 * Without Floodgate we fall back to the Floodgate UUID convention (most significant bits == 0).
 */
public final class FloodgateHook {

    private Object api;
    private Method isFloodgatePlayer;
    private final boolean available;

    public FloodgateHook(Logger log) {
        boolean ok = false;
        try {
            Class<?> type = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            this.api = type.getMethod("getInstance").invoke(null);
            this.isFloodgatePlayer = type.getMethod("isFloodgatePlayer", UUID.class);
            ok = api != null;
        } catch (Throwable t) {
            // Floodgate not installed or not ready
        }
        this.available = ok;
        if (ok) {
            log.info("Floodgate detected: Bedrock players are identified through the Floodgate API.");
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isBedrock(UUID uuid) {
        if (available) {
            try {
                return (Boolean) isFloodgatePlayer.invoke(api, uuid);
            } catch (Throwable ignored) {
                // fall through to heuristic
            }
        }
        return uuid.getMostSignificantBits() == 0L;
    }
}
