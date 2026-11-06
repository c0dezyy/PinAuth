package com.kodeshi.pinauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/** All authentication logic: sessions, PIN entry, registration, login, lockouts, PIN change, admin reset. */
public final class AuthManager {

    private final PinAuthPlugin plugin;
    private final Database db;
    private final PinHasher hasher;
    private final FloodgateHook floodgate;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, AccountRecord> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> registrationBlocks = new ConcurrentHashMap<>();

    public AuthManager(PinAuthPlugin plugin, Database db, PinHasher hasher, FloodgateHook floodgate) {
        this.plugin = plugin;
        this.db = db;
        this.hasher = hasher;
        this.floodgate = floodgate;
    }

    private Settings st() {
        return plugin.settings();
    }

    public Settings settings() {
        return plugin.settings();
    }

    public FloodgateHook floodgate() {
        return floodgate;
    }

    public Session session(UUID uuid) {
        return sessions.get(uuid);
    }

    /** Players created by NPC plugins (Citizens etc.) are never subject to authentication. */
    public boolean isExempt(Player p) {
        return p.hasMetadata("NPC");
    }

    /** True for every online player that has not completed authentication (fail-closed: no session = restricted). */
    public boolean isRestricted(Player p) {
        if (isExempt(p)) {
            return false;
        }
        Session s = sessions.get(p.getUniqueId());
        return s == null || !s.authenticated;
    }

    public static String formatDuration(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return m > 0 ? m + "m " + s + "s" : s + "s";
    }

    // ================================================================== connection lifecycle

    /**
     * Runs on the ASYNC pre-login thread, so blocking on the database is fine here.
     * Returns a kick message, or null to let the player in.
     */
    public Component preLogin(UUID uuid, String name) {
        Settings st = st();
        if (st.preventDuplicateLogin && sessions.containsKey(uuid)) {
            return st.text("duplicate-login-kick");
        }
        if (!st.allowBedrock && floodgate.isBedrock(uuid)) {
            return st.text("bedrock-disabled-kick");
        }
        long now = System.currentTimeMillis();
        Long block = registrationBlocks.get(uuid);
        if (block != null) {
            if (block > now) {
                return st.text("registration-blocked-kick", "time", formatDuration((block - now + 999) / 1000));
            }
            registrationBlocks.remove(uuid);
        }
        try {
            AccountRecord rec = db.loadOrCreate(uuid, name).get(10, TimeUnit.SECONDS);
            if (rec.lockoutUntil() > now) {
                return st.text("locked-kick", "time", formatDuration((rec.lockoutUntil() - now + 999) / 1000));
            }
            if (rec.lockoutUntil() != 0 || rec.failedCount() >= st.maxLoginAttempts) {
                // lockout expired (or stale counter): start fresh
                db.recordFailure(uuid, 0, 0L).get(10, TimeUnit.SECONDS);
                rec = rec.withFailures(0, 0L);
            }
            pending.put(uuid, rec);
            return null;
        } catch (Exception e) {
            plugin.getLogger().severe("Database error during login check: " + e);
            return st.text("service-unavailable-kick");
        }
    }

    public void onJoin(Player p) {
        if (isExempt(p)) {
            return;
        }
        AccountRecord rec = pending.remove(p.getUniqueId());
        if (rec == null) {
            kickPlayer(p, st().text("service-unavailable-kick"));
            return;
        }
        attach(p, rec);
    }

    /** For players already online when the plugin is enabled (e.g. after a plugin reload). */
    public void attachExisting(Player p) {
        if (isExempt(p)) {
            return;
        }
        db.loadOrCreate(p.getUniqueId(), p.getName()).whenComplete((rec, err) -> plugin.runSync(() -> {
            if (!p.isOnline()) {
                return;
            }
            if (err != null || rec == null) {
                kickPlayer(p, st().text("service-unavailable-kick"));
                return;
            }
            attach(p, rec);
        }));
    }

    private void attach(Player p, AccountRecord rec) {
        Settings st = st();
        Session s = new Session(p, rec);
        s.flow = rec.hasPin() ? Flow.LOGIN : Flow.REGISTER;
        Session old = sessions.put(s.uuid, s);
        if (old != null) {
            old.cancelTimeout();
            old.wipe();
        }
        String ip = p.getAddress() == null ? null : p.getAddress().getAddress().getHostAddress();
        if (st.isIpExempt(ip)) {
            s.authenticated = true;
            s.flow = Flow.NONE;
            logDbFailure(db.recordSuccess(s.uuid, s.name, System.currentTimeMillis()), "recording a trusted-IP login");
            p.sendMessage(st.msg("ip-exempt-login"));
            plugin.getLogger().info(s.name + " (" + s.uuid + ") logged in through a trusted-IP exemption (" + ip + ").");
            return;
        }
        p.sendMessage(st.msg(s.flow == Flow.LOGIN ? "welcome-login" : "welcome-register",
                "player", p.getName(), "length", st.lengthText()));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (isCurrent(s) && !s.authenticated) {
                openGui(s);
            }
        }, st.guiOpenDelayTicks);
        scheduleTimeout(s);
    }

    public void onQuit(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null && s.player == p) {
            s.leaving = true;
            s.cancelTimeout();
            s.wipe();
            sessions.remove(s.uuid, s);
        }
        pending.remove(p.getUniqueId());
    }

    /** Called from onDisable: nobody may keep playing unauthenticated once the plugin stops. */
    public void shutdown() {
        for (Session s : sessions.values()) {
            s.cancelTimeout();
            s.wipe();
            if (!s.authenticated && s.player.isOnline()) {
                s.leaving = true;
                s.player.kick(Component.text("The authentication system is restarting. Please rejoin."));
            }
        }
        sessions.clear();
        pending.clear();
    }

    private boolean isCurrent(Session s) {
        return sessions.get(s.uuid) == s && s.player.isOnline() && !s.leaving;
    }

    private boolean isValid(Session s, int generation) {
        return isCurrent(s) && s.generation == generation;
    }

    private void kick(Session s, Component reason) {
        s.leaving = true;
        s.cancelTimeout();
        s.wipe();
        s.player.kick(reason);
    }

    private void kickPlayer(Player p, Component reason) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null) {
            s.leaving = true;
        }
        p.kick(reason);
    }

    private void scheduleTimeout(Session s) {
        s.cancelTimeout();
        int seconds = st().loginTimeoutSeconds;
        if (seconds <= 0 || s.authenticated) {
            return;
        }
        s.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            s.timeoutTask = null;
            if (isCurrent(s) && !s.authenticated) {
                kick(s, st().text("timeout-kick"));
            }
        }, seconds * 20L);
    }

    // ================================================================== GUI handling

    public void openGui(Session s) {
        if (!s.player.isOnline() || s.leaving) {
            return;
        }
        Inventory inv = PinGui.create(st(), s);
        s.suppressClose = true;
        try {
            s.player.openInventory(inv);
        } finally {
            s.suppressClose = false;
        }
    }

    private void closeGui(Session s) {
        s.suppressClose = true;
        try {
            Inventory top = s.player.getOpenInventory().getTopInventory();
            if (top.getHolder(false) instanceof PinGui.Holder) {
                s.player.closeInventory();
            }
        } finally {
            s.suppressClose = false;
        }
    }

    private void refresh(Session s) {
        Inventory top = s.player.getOpenInventory().getTopInventory();
        if (top.getHolder(false) instanceof PinGui.Holder) {
            PinGui.renderInput(st(), top, s.input.length());
        }
    }

    public void handleClick(Session s, Inventory inv, int slot) {
        if (s.busy || s.leaving) {
            return;
        }
        Settings st = st();
        for (int d = 0; d <= 9; d++) {
            if (slot == st.slotDigits[d]) {
                if (s.input.length() < st.maxPinLength) {
                    s.input.append((char) ('0' + d));
                    PinGui.renderInput(st, inv, s.input.length());
                }
                return;
            }
        }
        if (slot == st.slotClear) {
            s.clearInput();
            PinGui.renderInput(st, inv, 0);
        } else if (slot == st.slotDelete) {
            if (s.input.length() > 0) {
                s.input.setLength(s.input.length() - 1);
            }
            PinGui.renderInput(st, inv, s.input.length());
        } else if (slot == st.slotConfirm) {
            submit(s);
        } else if (slot == st.slotCancel) {
            if (!s.authenticated) {
                kick(s, st.text("cancel-kick"));
            } else {
                cancelChange(s);
            }
        }
    }

    /** Called when a PinAuth inventory is closed by the player (not by the plugin). */
    public void onGuiClose(Session s) {
        if (s.suppressClose || s.leaving) {
            return;
        }
        Settings st = st();
        if (s.authenticated) {
            if (s.flow.isChange() && !s.busy) {
                endChange(s);
                s.player.sendMessage(st.msg("change-cancelled"));
            }
            return;
        }
        s.clearInput();
        s.closeCount++;
        if (s.closeCount > st.maxGuiCloses) {
            kick(s, st.text("gui-closed-kick"));
            return;
        }
        if (st.reopenOnClose) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (isCurrent(s) && !s.authenticated) {
                    openGui(s);
                }
            }, st.guiReopenDelayTicks);
        } else {
            s.player.sendMessage(st.msg("gui-closed-hint"));
        }
    }

    /** /pin */
    public void reopen(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.leaving) {
            return;
        }
        if (s.authenticated) {
            p.sendMessage(st().msg("already-authenticated"));
        } else {
            openGui(s);
        }
    }

    // ================================================================== PIN submission

    private void submit(Session s) {
        Settings st = st();
        Flow flow = s.flow;
        if (flow == Flow.NONE) {
            return;
        }
        String pin = s.input.toString();
        s.clearInput();
        refresh(s);
        if (pin.length() < st.minPinLength || pin.length() > st.maxPinLength) {
            s.player.sendMessage(st.msg("pin-incomplete", "length", st.lengthText()));
            return;
        }
        boolean verifying = flow == Flow.LOGIN || flow == Flow.CHANGE_OLD;
        if (verifying) {
            long now = System.nanoTime();
            if (now - s.lastVerifyNanos < st.attemptCooldownMillis * 1_000_000L) {
                s.player.sendMessage(st.msg("slow-down"));
                return;
            }
            s.lastVerifyNanos = now;
        }
        switch (flow) {
            case LOGIN, CHANGE_OLD -> verify(s, pin, flow);
            case REGISTER, CHANGE_NEW -> enterNew(s, pin, flow);
            case REGISTER_CONFIRM, CHANGE_CONFIRM -> confirmNew(s, pin, flow);
            default -> { }
        }
    }

    private void verify(Session s, String pin, Flow flow) {
        s.busy = true;
        int gen = s.generation;
        String hash = s.record.pinHash();
        plugin.runAsync(() -> {
            boolean ok = hasher.verify(pin, hash);
            plugin.runSync(() -> {
                if (!isValid(s, gen)) {
                    return;
                }
                s.busy = false;
                if (ok) {
                    onVerified(s, flow);
                } else {
                    onWrongPin(s);
                }
            });
        });
    }

    private void onVerified(Session s, Flow flow) {
        Settings st = st();
        s.failedCount = 0;
        s.record = s.record.withFailures(0, 0L);
        if (flow == Flow.LOGIN) {
            s.authenticated = true;
            s.flow = Flow.NONE;
            s.closeCount = 0;
            s.cancelTimeout();
            logDbFailure(db.recordSuccess(s.uuid, s.name, System.currentTimeMillis()), "recording a login");
            closeGui(s);
            s.player.sendMessage(st.msg("login-success"));
        } else {
            logDbFailure(db.resetFailures(s.uuid), "resetting the failure counter");
            s.flow = Flow.CHANGE_NEW;
            s.player.sendMessage(st.msg("change-old-ok"));
            openGui(s);
        }
    }

    private void onWrongPin(Session s) {
        Settings st = st();
        s.failedCount++;
        if (s.failedCount >= st.maxLoginAttempts) {
            boolean lockout = st.lockoutDurationSeconds > 0;
            long until = lockout ? System.currentTimeMillis() + st.lockoutDurationSeconds * 1000L : 0L;
            logDbFailure(db.recordFailure(s.uuid, lockout ? s.failedCount : 0, until), "recording a lockout");
            plugin.getLogger().warning(s.name + " (" + s.uuid + ") used all PIN attempts"
                    + (lockout ? "; account locked for " + formatDuration(st.lockoutDurationSeconds) : ""));
            kick(s, st.text("too-many-attempts"));
            return;
        }
        logDbFailure(db.recordFailure(s.uuid, s.failedCount, 0L), "recording a failed attempt");
        s.player.sendMessage(st.msg("incorrect-pin", "remaining", Integer.toString(st.maxLoginAttempts - s.failedCount)));
    }

    private void enterNew(Session s, String pin, Flow flow) {
        Settings st = st();
        if (st.rejectWeakPins && isWeak(pin)) {
            s.player.sendMessage(st.msg("pin-weak"));
            return;
        }
        boolean registering = flow == Flow.REGISTER;
        if (!st.requireConfirmation) {
            storeNew(s, pin, registering);
            return;
        }
        s.firstEntry = pin;
        s.flow = registering ? Flow.REGISTER_CONFIRM : Flow.CHANGE_CONFIRM;
        s.player.sendMessage(st.msg("confirm-prompt"));
        openGui(s);
    }

    private void confirmNew(Session s, String pin, Flow flow) {
        Settings st = st();
        boolean registering = flow == Flow.REGISTER_CONFIRM;
        String first = s.firstEntry;
        s.firstEntry = null;
        boolean same = first != null && MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8), pin.getBytes(StandardCharsets.UTF_8));
        if (!same) {
            s.regFailures++;
            if (registering && s.regFailures >= st.maxRegistrationFailures) {
                registrationBlocks.put(s.uuid, System.currentTimeMillis() + st.registrationBlockSeconds * 1000L);
                kick(s, st.text("registration-too-many"));
                return;
            }
            s.flow = registering ? Flow.REGISTER : Flow.CHANGE_NEW;
            s.player.sendMessage(st.msg("confirm-mismatch"));
            openGui(s);
            return;
        }
        storeNew(s, pin, registering);
    }

    private void storeNew(Session s, String pin, boolean registering) {
        Settings st = st();
        s.busy = true;
        int gen = s.generation;
        int iterations = st.pbkdf2Iterations;
        boolean pepper = st.usePepper;
        long now = System.currentTimeMillis();
        plugin.runAsync(() -> {
            String hash;
            try {
                hash = hasher.hash(pin, iterations, pepper);
            } catch (RuntimeException e) {
                plugin.getLogger().severe("PIN hashing failed: " + e.getMessage());
                plugin.runSync(() -> {
                    if (isValid(s, gen)) {
                        s.busy = false;
                        kick(s, st().text("service-unavailable-kick"));
                    }
                });
                return;
            }
            CompletableFuture<Boolean> write = registering
                    ? db.register(s.uuid, s.name, hash, now)
                    : db.changePin(s.uuid, hash);
            write.whenComplete((ok, err) -> plugin.runSync(() -> {
                boolean sameSession = sessions.get(s.uuid) == s;
                if (sameSession && s.generation != gen) {
                    // An admin reset happened while we were saving: the reset wins.
                    if (err == null && Boolean.TRUE.equals(ok)) {
                        logDbFailure(db.clearPin(s.uuid), "re-applying a PIN reset");
                    }
                    return;
                }
                if (!sameSession || !s.player.isOnline() || s.leaving) {
                    return;
                }
                s.busy = false;
                Settings cur = st();
                if (err != null) {
                    plugin.getLogger().severe("Database error while saving a PIN: " + err);
                    if (registering) {
                        kick(s, cur.text("service-unavailable-kick"));
                    } else {
                        endChange(s);
                        closeGui(s);
                        s.player.sendMessage(cur.msg("change-failed"));
                    }
                    return;
                }
                if (!Boolean.TRUE.equals(ok)) {
                    if (registering) {
                        kick(s, cur.text("registration-already"));
                    } else {
                        endChange(s);
                        closeGui(s);
                        s.player.sendMessage(cur.msg("change-failed"));
                    }
                    return;
                }
                s.failedCount = 0;
                s.regFailures = 0;
                if (registering) {
                    s.record = s.record.withPin(hash, now);
                    s.authenticated = true;
                    s.flow = Flow.NONE;
                    s.closeCount = 0;
                    s.cancelTimeout();
                    closeGui(s);
                    s.player.sendMessage(cur.msg("registration-success"));
                    plugin.getLogger().info(s.name + " (" + s.uuid + ") registered a PIN.");
                } else {
                    s.record = s.record.withPinHash(hash);
                    endChange(s);
                    closeGui(s);
                    s.player.sendMessage(cur.msg("change-success"));
                    plugin.getLogger().info(s.name + " (" + s.uuid + ") changed their PIN.");
                }
            }));
        });
    }

    private static boolean isWeak(String pin) {
        if (pin.chars().distinct().count() == 1) {
            return true;
        }
        boolean asc = true;
        boolean desc = true;
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) != pin.charAt(i - 1) + 1) {
                asc = false;
            }
            if (pin.charAt(i) != pin.charAt(i - 1) - 1) {
                desc = false;
            }
        }
        return asc || desc;
    }

    // ================================================================== PIN change (/changepin)

    public void startChange(Player p) {
        Settings st = st();
        Session s = sessions.get(p.getUniqueId());
        if (s == null || !s.authenticated) {
            return;
        }
        if (!s.record.hasPin()) {
            p.sendMessage(st.msg("change-not-registered"));
            return;
        }
        if (s.busy || s.flow != Flow.NONE) {
            p.sendMessage(st.msg("change-busy"));
            return;
        }
        s.clearInput();
        s.firstEntry = null;
        s.regFailures = 0;
        s.flow = Flow.CHANGE_OLD;
        p.sendMessage(st.msg("change-start"));
        openGui(s);
    }

    private void endChange(Session s) {
        s.flow = Flow.NONE;
        s.firstEntry = null;
        s.clearInput();
    }

    private void cancelChange(Session s) {
        endChange(s);
        closeGui(s);
        s.player.sendMessage(st().msg("change-cancelled"));
    }

    // ================================================================== admin reset

    /** Clears the PIN of the account (online or offline) and forces re-registration. Main thread only. */
    public void adminReset(CommandSender sender, UUID uuid, String name) {
        db.clearPin(uuid).whenComplete((rows, err) -> plugin.runSync(() -> {
            Settings st = st();
            if (err != null) {
                plugin.getLogger().severe("Database error while clearing a PIN: " + err);
                sender.sendMessage(st.msg("admin-db-error"));
                return;
            }
            Session s = sessions.get(uuid);
            if ((rows == null || rows == 0) && s == null) {
                sender.sendMessage(st.msg("admin-not-found", "name", name));
                return;
            }
            if (s != null) {
                forceRegistration(s);
            }
            sender.sendMessage(st.msg("admin-reset-done", "player", name));
            plugin.getLogger().info("Admin " + sender.getName() + " cleared the PIN of " + name + " (" + uuid + ").");
        }));
    }

    private void forceRegistration(Session s) {
        s.generation++;           // invalidates any in-flight verification/save
        s.authenticated = false;
        s.flow = Flow.REGISTER;
        s.busy = false;
        s.clearInput();
        s.firstEntry = null;
        s.failedCount = 0;
        s.regFailures = 0;
        s.closeCount = 0;
        s.record = s.record.cleared();
        s.player.sendMessage(st().msg("admin-reset-notify"));
        scheduleTimeout(s);
        openGui(s);
    }

    private void logDbFailure(CompletableFuture<?> future, String what) {
        future.exceptionally(err -> {
            plugin.getLogger().severe("Database error while " + what + ": " + err);
            return null;
        });
    }
}
