package com.kodeshi.pinauth;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core authentication logic. Default-deny: a player is unauthenticated unless their UUID is in the
 * authenticated set, which is cleared on every disconnect. All state changes happen on the main thread;
 * hashing and SQLite run on background pools.
 */
public final class AuthManager {

    private enum Type { OK, WRONG, TRIPPED, LOCKED, NO_PIN }

    private record Outcome(Type type, int remaining, long lockoutUntil) {
    }

    private final PinAuthPlugin plugin;
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PinSession> sessions = new ConcurrentHashMap<>();

    public AuthManager(PinAuthPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ state queries

    public boolean isAuthenticated(Player player) {
        // Citizens/other NPC "players" are never real connections; do not restrict them.
        return authenticated.contains(player.getUniqueId()) || player.hasMetadata("NPC");
    }

    public boolean isRestricted(Player player) {
        return !isAuthenticated(player);
    }

    public PinSession session(UUID id) {
        return sessions.get(id);
    }

    // ------------------------------------------------------------------ session lifecycle

    /** Called on join (and when an admin resets an online player's PIN). */
    public void beginSession(Player player, boolean announce) {
        UUID id = player.getUniqueId();
        Settings st = plugin.settings();
        authenticated.remove(id);
        discardSession(player);

        if (!plugin.isReady()) {
            kick(player, "auth-unavailable-kick");
            return;
        }
        boolean bedrock = plugin.floodgate().isBedrock(player);
        if (bedrock && !st.allowBedrockPlayers) {
            kick(player, "bedrock-disabled-kick");
            return;
        }

        PinSession s = new PinSession(id);
        sessions.put(id, s);
        if (announce) player.sendMessage(st.message("welcome"));

        plugin.database().loadOrCreate(id, player.getName(), bedrock ? "BEDROCK" : "JAVA")
                .whenComplete((acc, err) -> plugin.runMain(() -> {
                    if (sessions.get(id) != s || !player.isOnline()) return;
                    Settings now = plugin.settings();
                    if (err != null || acc == null) {
                        kick(player, "auth-unavailable-kick");
                        return;
                    }
                    long time = System.currentTimeMillis();
                    if (acc.lockoutUntil() > time) {
                        kick(player, "login-locked-kick", "time", Text.duration(acc.lockoutUntil() - time));
                        return;
                    }
                    if (acc.hasPin()) {
                        s.stage = PinSession.Stage.LOGIN;
                        player.sendMessage(now.message("login-start"));
                    } else {
                        s.stage = PinSession.Stage.REGISTER_ENTER;
                        player.sendMessage(now.message("register-start"));
                    }
                    openGui(player, s);
                    scheduleTimeout(player, s);
                }));
    }

    /** Admin reset of an online player: revoke the session and force re-registration. */
    public void revoke(Player player) {
        authenticated.remove(player.getUniqueId());
        beginSession(player, false);
        player.sendMessage(plugin.settings().message("admin-reset-notify"));
        player.updateCommands();
    }

    public void handleQuit(UUID id) {
        authenticated.remove(id);
        dropSession(id);
    }

    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isRestricted(p)) {
                p.kick(plugin.settings().plain("plugin-disabled-kick"));
            }
        }
        for (UUID id : sessions.keySet()) dropSession(id);
        authenticated.clear();
    }

    /** Re-renders open keypads after a config reload. */
    public void refreshAll() {
        Settings st = plugin.settings();
        for (Map.Entry<UUID, PinSession> e : sessions.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            PinSession s = e.getValue();
            if (p == null || s.inventory == null || s.finished || !ourGuiOpen(p)) continue;
            if (s.input.length() > st.maxPinLength) s.input.setLength(st.maxPinLength);
            PinGui.renderAll(s.inventory, p, s, st);
        }
    }

    /** /pinlogin: re-open the keypad. */
    public void reopen(Player player) {
        PinSession s = sessions.get(player.getUniqueId());
        if (s == null || s.finished) {
            player.sendMessage(plugin.settings().message(isAuthenticated(player) ? "already-authenticated"
                    : "not-in-session"));
            return;
        }
        if (s.inventory == null) return; // account still loading
        if (!ourGuiOpen(player)) openGui(player, s);
    }

    /** /changepin: start the old-PIN -> new-PIN -> confirm flow for an authenticated player. */
    public void startChange(Player player) {
        UUID id = player.getUniqueId();
        Settings st = plugin.settings();
        if (!isAuthenticated(player) || sessions.containsKey(id)) return;
        if (!plugin.isReady()) {
            player.sendMessage(st.message("admin-error"));
            return;
        }
        PinSession s = new PinSession(id);
        s.stage = PinSession.Stage.CHANGE_OLD;
        sessions.put(id, s);
        player.sendMessage(st.message("changepin-start"));
        openGui(player, s);
    }

    // ------------------------------------------------------------------ GUI events

    public void handleClose(Player player, PinGui.Holder holder) {
        PinSession s = holder.session();
        UUID id = player.getUniqueId();
        if (s.suppressClose || s.finished || sessions.get(id) != s) return;
        Settings st = plugin.settings();

        if (s.stage.isChange()) { // closing the keypad aborts a PIN change
            dropSession(id);
            player.sendMessage(st.message("changepin-cancelled"));
            return;
        }
        if (isAuthenticated(player)) return;

        s.closeCount++;
        if (st.maxGuiCloses > 0 && s.closeCount >= st.maxGuiCloses) {
            kick(player, "gui-closed-kick");
            return;
        }
        if (st.reopenGuiOnClose) {
            // Inventories cannot be opened from inside the close event; do it next tick.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && sessions.get(id) == s && !s.finished && !isAuthenticated(player)
                        && s.inventory != null && !ourGuiOpen(player)) {
                    openGui(player, s);
                }
            });
        } else {
            player.sendMessage(st.message("gui-closed-hint"));
        }
    }

    public void handleClick(Player player, PinSession s, int slot) {
        if (s.finished || s.busy || s.inventory == null) return;
        Settings st = plugin.settings();

        int digit = st.digitForSlot(slot);
        if (digit >= 0) {
            if (s.input.length() < st.maxPinLength) s.input.append((char) ('0' + digit));
            click(player);
            PinGui.renderPin(s.inventory, s, st);
        } else if (slot == st.backspaceSlot) {
            if (s.input.length() > 0) s.input.deleteCharAt(s.input.length() - 1);
            click(player);
            PinGui.renderPin(s.inventory, s, st);
        } else if (slot == st.clearSlot) {
            s.input.setLength(0);
            click(player);
            PinGui.renderPin(s.inventory, s, st);
        } else if (slot == st.confirmSlot) {
            click(player);
            submit(player, s);
        } else if (st.cancelSlot >= 0 && slot == st.cancelSlot) {
            cancel(player, s);
        }
    }

    private void cancel(Player player, PinSession s) {
        if (s.stage.isChange()) {
            discardSession(player);
            player.sendMessage(plugin.settings().message("changepin-cancelled"));
        } else {
            kick(player, "cancel-kick");
        }
    }

    // ------------------------------------------------------------------ submit / state machine

    private void submit(Player player, PinSession s) {
        Settings st = plugin.settings();
        String pin = s.input.toString();
        if (pin.length() < st.minPinLength || pin.length() > st.maxPinLength) {
            player.sendMessage(st.message("pin-too-short", "length", st.lengthDescription()));
            return;
        }
        boolean guessable = s.stage == PinSession.Stage.LOGIN || s.stage == PinSession.Stage.CHANGE_OLD;
        long now = System.currentTimeMillis();
        if (guessable) {
            if (now - s.lastAttempt < st.attemptCooldownMillis) {
                player.sendMessage(st.message("slow-down"));
                return;
            }
            s.lastAttempt = now;
        }

        switch (s.stage) {
            case LOGIN -> attempt(player, s, pin, true);
            case CHANGE_OLD -> attempt(player, s, pin, false);

            case REGISTER_ENTER -> {
                if (!st.requirePinConfirmation) {
                    register(player, s, pin);
                } else {
                    s.firstPin = pin;
                    s.input.setLength(0);
                    s.stage = PinSession.Stage.REGISTER_CONFIRM;
                    rerender(player, s);
                    player.sendMessage(st.message("register-confirm-start"));
                }
            }
            case REGISTER_CONFIRM -> {
                if (pin.equals(s.firstPin)) {
                    register(player, s, pin);
                } else {
                    s.registrationFailures++;
                    s.wipe();
                    s.stage = PinSession.Stage.REGISTER_ENTER;
                    rerender(player, s);
                    player.sendMessage(st.message("register-mismatch"));
                    if (st.maxRegistrationAttempts > 0 && s.registrationFailures >= st.maxRegistrationAttempts) {
                        kick(player, "register-too-many-failures");
                    }
                }
            }

            case CHANGE_NEW -> {
                if (!st.requirePinConfirmation) {
                    changePin(player, s, pin);
                } else {
                    s.firstPin = pin;
                    s.input.setLength(0);
                    s.stage = PinSession.Stage.CHANGE_CONFIRM;
                    rerender(player, s);
                    player.sendMessage(st.message("changepin-confirm-start"));
                }
            }
            case CHANGE_CONFIRM -> {
                if (pin.equals(s.firstPin)) {
                    changePin(player, s, pin);
                } else {
                    s.wipe();
                    s.stage = PinSession.Stage.CHANGE_NEW;
                    rerender(player, s);
                    player.sendMessage(st.message("changepin-mismatch"));
                }
            }
        }
    }

    // ---- verify (login or "old PIN" of a change)

    private void attempt(Player player, PinSession s, String pin, boolean loginFlow) {
        UUID id = player.getUniqueId();
        Settings st = plugin.settings();
        Database db = plugin.database();
        PinHasher hasher = plugin.hasher();
        s.busy = true;
        char[] chars = pin.toCharArray();
        long started = System.currentTimeMillis();

        db.get(id)
                .thenApplyAsync(acc -> verify(hasher, acc, chars, started), plugin.hashPool())
                .thenCompose(first -> afterVerify(db, st, id, first, loginFlow))
                .whenComplete((outcome, err) -> plugin.runMain(() -> {
                    Arrays.fill(chars, '\0');
                    finishAttempt(player, s, outcome, err, loginFlow);
                }));
    }

    private Outcome verify(PinHasher hasher, Account acc, char[] pin, long now) {
        if (acc == null || !acc.hasPin()) return new Outcome(Type.NO_PIN, 0, 0);
        if (acc.lockoutUntil() > now) return new Outcome(Type.LOCKED, 0, acc.lockoutUntil());
        boolean ok = hasher.verify(pin, acc.pinHash());
        return new Outcome(ok ? Type.OK : Type.WRONG, 0, 0);
    }

    private CompletableFuture<Outcome> afterVerify(Database db, Settings st, UUID id, Outcome first,
                                                   boolean loginFlow) {
        if (first.type() == Type.WRONG) {
            return db.recordFailure(id, st.maxLoginAttempts, st.lockoutMillis(), System.currentTimeMillis())
                    .thenApply(fr -> fr.locked()
                            ? new Outcome(Type.TRIPPED, 0, fr.lockoutUntil())
                            : new Outcome(Type.WRONG, Math.max(0, st.maxLoginAttempts - fr.failedCount()), 0));
        }
        if (first.type() == Type.OK) {
            return db.recordSuccess(id, System.currentTimeMillis(), loginFlow)
                    .thenApply(ok -> ok ? first : new Outcome(Type.NO_PIN, 0, 0));
        }
        return CompletableFuture.completedFuture(first);
    }

    private void finishAttempt(Player player, PinSession s, Outcome o, Throwable err, boolean loginFlow) {
        if (sessions.get(player.getUniqueId()) != s || s.finished || !player.isOnline()) return;
        s.busy = false;
        Settings st = plugin.settings();
        if (err != null || o == null) {
            kick(player, "auth-unavailable-kick");
            return;
        }
        switch (o.type()) {
            case OK -> {
                if (loginFlow) {
                    authenticate(player, s, "login-success");
                } else {
                    s.wipe();
                    s.stage = PinSession.Stage.CHANGE_NEW;
                    rerender(player, s);
                    player.sendMessage(st.message("changepin-new-start"));
                }
            }
            case WRONG -> {
                s.input.setLength(0);
                PinGui.renderPin(s.inventory, s, st);
                player.sendMessage(st.message("login-wrong-pin", "remaining", String.valueOf(o.remaining())));
            }
            case TRIPPED -> kick(player, "login-too-many-kick");
            case LOCKED -> kick(player, "login-locked-kick", "time",
                    Text.duration(o.lockoutUntil() - System.currentTimeMillis()));
            case NO_PIN -> {
                // The PIN was cleared (or registered elsewhere) while we were verifying.
                if (loginFlow) {
                    s.wipe();
                    s.stage = PinSession.Stage.REGISTER_ENTER;
                    openGui(player, s);
                    player.sendMessage(st.message("register-start"));
                } else {
                    discardSession(player);
                    player.sendMessage(st.message("changepin-cancelled"));
                }
            }
        }
    }

    // ---- register

    private void register(Player player, PinSession s, String pin) {
        UUID id = player.getUniqueId();
        PinHasher hasher = plugin.hasher();
        Database db = plugin.database();
        char[] chars = pin.toCharArray();
        s.busy = true;

        CompletableFuture.supplyAsync(() -> hasher.hash(chars), plugin.hashPool())
                .thenCompose(hash -> db.registerPin(id, hash, System.currentTimeMillis()))
                .whenComplete((ok, err) -> plugin.runMain(() -> {
                    Arrays.fill(chars, '\0');
                    if (sessions.get(id) != s || s.finished || !player.isOnline()) return;
                    s.busy = false;
                    Settings st = plugin.settings();
                    if (err != null || ok == null) {
                        kick(player, "auth-unavailable-kick");
                    } else if (ok) {
                        authenticate(player, s, "register-success");
                    } else {
                        // Duplicate registration attempt: the account already has a PIN.
                        s.wipe();
                        s.stage = PinSession.Stage.LOGIN;
                        openGui(player, s);
                        player.sendMessage(st.message("already-registered"));
                    }
                }));
    }

    // ---- change

    private void changePin(Player player, PinSession s, String pin) {
        UUID id = player.getUniqueId();
        PinHasher hasher = plugin.hasher();
        Database db = plugin.database();
        char[] chars = pin.toCharArray();
        s.busy = true;

        CompletableFuture.supplyAsync(() -> hasher.hash(chars), plugin.hashPool())
                .thenCompose(hash -> db.changePin(id, hash))
                .whenComplete((ok, err) -> plugin.runMain(() -> {
                    Arrays.fill(chars, '\0');
                    if (sessions.get(id) != s || s.finished || !player.isOnline()) return;
                    s.busy = false;
                    Settings st = plugin.settings();
                    discardSession(player);
                    if (err != null || ok == null || !ok) {
                        player.sendMessage(st.message("admin-error"));
                    } else {
                        player.sendMessage(st.message("changepin-success"));
                    }
                }));
    }

    // ------------------------------------------------------------------ helpers

    private void authenticate(Player player, PinSession s, String messageKey) {
        authenticated.add(player.getUniqueId());
        s.finished = true;
        discardSession(player);
        player.updateCommands();
        player.sendMessage(plugin.settings().message(messageKey));
    }

    private void openGui(Player player, PinSession s) {
        Inventory inv = PinGui.create(player, s, plugin.settings());
        s.inventory = inv;
        s.suppressClose = true;
        try {
            player.openInventory(inv);
        } finally {
            s.suppressClose = false;
        }
    }

    private void rerender(Player player, PinSession s) {
        if (s.inventory != null) PinGui.renderAll(s.inventory, player, s, plugin.settings());
    }

    private boolean ourGuiOpen(Player player) {
        return player.getOpenInventory().getTopInventory().getHolder(false) instanceof PinGui.Holder;
    }

    /** Ends the player's session and closes our keypad if it is open. */
    private void discardSession(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder(false) instanceof PinGui.Holder holder) {
            holder.session().finished = true;
            player.closeInventory();
        }
        dropSession(player.getUniqueId());
    }

    /** Ends the session without touching any inventory (safe inside close events and on quit). */
    private void dropSession(UUID id) {
        PinSession s = sessions.remove(id);
        if (s == null) return;
        s.finished = true;
        if (s.timeoutTask != null) s.timeoutTask.cancel();
        s.wipe();
    }

    private void scheduleTimeout(Player player, PinSession s) {
        int seconds = plugin.settings().loginTimeoutSeconds;
        if (seconds <= 0) return;
        s.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (sessions.get(player.getUniqueId()) == s && !s.finished && player.isOnline()
                    && !isAuthenticated(player)) {
                kick(player, "timeout-kick");
            }
        }, seconds * 20L);
    }

    private void kick(Player player, String key, String... pairs) {
        player.kick(plugin.settings().plain(key, pairs));
    }

    private void click(Player player) {
        if (plugin.settings().clickSounds) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.3f);
        }
    }
}
