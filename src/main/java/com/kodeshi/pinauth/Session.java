package com.kodeshi.pinauth;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** Per-connection authentication state. Only touched on the main server thread. */
public final class Session {

    public final UUID uuid;
    public final String name;
    public final Player player;

    public AccountRecord record;
    public boolean authenticated;
    public Flow flow = Flow.NONE;
    public final StringBuilder input = new StringBuilder(8);
    public String firstEntry;          // first PIN entry while confirming (memory only)
    public int generation;             // bumped on admin reset; invalidates in-flight async work
    public boolean busy;               // hashing / database work in flight
    public boolean suppressClose;      // set while the plugin itself opens/closes the GUI
    public boolean leaving;            // kick already issued
    public int failedCount;
    public int regFailures;
    public int closeCount;
    public long lastVerifyNanos = System.nanoTime() - TimeUnit.HOURS.toNanos(1);
    public BukkitTask timeoutTask;

    public Session(Player player, AccountRecord record) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
        this.player = player;
        this.record = record;
        this.failedCount = record.failedCount();
    }

    public void clearInput() {
        input.setLength(0);
    }

    public void cancelTimeout() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
    }

    public void wipe() {
        clearInput();
        firstEntry = null;
    }
}
