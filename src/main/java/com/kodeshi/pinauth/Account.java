package com.kodeshi.pinauth;

import java.util.UUID;

/** One row of the accounts table. toString() deliberately omits the hash. */
public record Account(UUID uuid, String username, String pinHash, String status,
                      long registeredAt, long lastLogin, int failedCount,
                      long lockoutUntil, String edition) {

    public static final String STATUS_REGISTERED = "REGISTERED";
    public static final String STATUS_NEEDS_REGISTRATION = "NEEDS_REGISTRATION";

    public boolean hasPin() {
        return pinHash != null && !pinHash.isEmpty() && STATUS_REGISTERED.equals(status);
    }

    @Override
    public String toString() {
        return "Account[uuid=" + uuid + ", username=" + username + ", status=" + status + "]";
    }
}
