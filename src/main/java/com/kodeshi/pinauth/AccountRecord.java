package com.kodeshi.pinauth;

import java.util.UUID;

/** Immutable snapshot of one row of the accounts table. */
public record AccountRecord(
        UUID uuid,
        String username,
        String pinHash,
        AccountStatus status,
        long registeredAt,
        long lastLogin,
        int failedCount,
        long lockoutUntil) {

    public boolean hasPin() {
        return status == AccountStatus.REGISTERED && pinHash != null;
    }

    public AccountRecord withFailures(int failed, long lockout) {
        return new AccountRecord(uuid, username, pinHash, status, registeredAt, lastLogin, failed, lockout);
    }

    public AccountRecord withPin(String newHash, long now) {
        return new AccountRecord(uuid, username, newHash, AccountStatus.REGISTERED,
                registeredAt == 0 ? now : registeredAt, lastLogin, 0, 0);
    }

    public AccountRecord withPinHash(String newHash) {
        return new AccountRecord(uuid, username, newHash, status, registeredAt, lastLogin, failedCount, lockoutUntil);
    }

    public AccountRecord cleared() {
        return new AccountRecord(uuid, username, null, AccountStatus.NEEDS_REGISTRATION,
                registeredAt, lastLogin, 0, 0);
    }

    /** Never include the hash in logs or messages. */
    @Override
    public String toString() {
        return "AccountRecord[uuid=" + uuid + ", status=" + status + "]";
    }
}
