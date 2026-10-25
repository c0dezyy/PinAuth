package com.kodeshi.pinauth;

import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/** Per-player GUI/authentication state. Only touched on the main server thread. */
public final class PinSession {

    public enum Stage {
        LOGIN, REGISTER_ENTER, REGISTER_CONFIRM, CHANGE_OLD, CHANGE_NEW, CHANGE_CONFIRM;

        public boolean isChange() {
            return this == CHANGE_OLD || this == CHANGE_NEW || this == CHANGE_CONFIRM;
        }

        /** Key used in config.yml gui.prompts. */
        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    final UUID uuid;
    Stage stage = Stage.LOGIN;
    final StringBuilder input = new StringBuilder();
    String firstPin;            // first entry while confirming (registration / new PIN)
    boolean busy;               // a hash/DB operation is in flight
    boolean suppressClose;      // set while the plugin itself opens/closes the GUI
    boolean finished;           // session is over; ignore further events
    int closeCount;
    int registrationFailures;
    long lastAttempt;
    Inventory inventory;
    BukkitTask timeoutTask;

    PinSession(UUID uuid) {
        this.uuid = uuid;
    }

    void wipe() {
        input.setLength(0);
        firstPin = null;
    }
}
