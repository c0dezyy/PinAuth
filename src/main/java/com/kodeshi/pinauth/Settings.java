package com.kodeshi.pinauth;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Immutable, validated snapshot of config.yml. Invalid values fall back to defaults with a warning. */
public final class Settings {
    public static final int GUI_SIZE = 54;
    public static final String NAMESPACE = "crossplaypinauth:";

    private static final int[] DEFAULT_PIN_SLOTS = {11, 12, 13, 14, 15, 16};
    private static final int[] DEFAULT_DIGITS = {49, 21, 22, 23, 30, 31, 32, 39, 40, 41};
    private static final int DEF_INFO = 4, DEF_CLEAR = 48, DEF_BACK = 50, DEF_CONFIRM = 53, DEF_CANCEL = 45;

    public final int minPinLength;
    public final int maxPinLength;
    public final int maxLoginAttempts;
    public final int loginTimeoutSeconds;
    public final int lockoutDurationSeconds;
    public final int maxGuiCloses;
    public final int maxRegistrationAttempts;
    public final int hashIterations;
    public final long attemptCooldownMillis;
    public final boolean allowBedrockPlayers;
    public final boolean requirePinConfirmation;
    public final boolean freezeUnauthenticatedPlayers;
    public final boolean reopenGuiOnClose;
    public final boolean blockDuplicateLogin;
    public final boolean clickSounds;
    public final boolean showDigits;
    public final boolean cancelButtonEnabled;
    public final String databaseFile;
    public final Set<String> allowedCommands;

    public final String titleLogin;
    public final String titleRegister;
    public final String titleChange;

    public final Material fillerMaterial;
    public final Material infoMaterial;
    public final Material digitMaterial;
    public final Material clearMaterial;
    public final Material backspaceMaterial;
    public final Material confirmMaterial;
    public final Material cancelMaterial;
    public final Material pinEmptyMaterial;
    public final Material pinFilledMaterial;

    public final String nameClear;
    public final String nameBackspace;
    public final String nameConfirm;
    public final String nameCancel;
    public final String namePinEmpty;
    public final String namePinFilled;

    public final int[] pinSlots;
    public final int infoSlot;
    public final int[] digitSlots;
    public final int clearSlot;
    public final int backspaceSlot;
    public final int confirmSlot;
    public final int cancelSlot; // -1 when disabled

    private final String prefix;
    private final Map<String, String> messages;
    private final Map<String, String> prompts;
    private final List<String> infoLore;

    public Settings(FileConfiguration c, Logger log) {
        int minLen = intIn(c, log, "min-pin-length", 6, 4, 8);
        int maxLen = intIn(c, log, "max-pin-length", 6, 4, 8);
        if (minLen > maxLen) {
            log.warning("min-pin-length is greater than max-pin-length; using " + maxLen + " for both.");
            minLen = maxLen;
        }

        this.maxLoginAttempts = intIn(c, log, "max-login-attempts", 4, 1, 50);
        this.loginTimeoutSeconds = intIn(c, log, "login-timeout-seconds", 0, 0, 86400);
        this.lockoutDurationSeconds = intIn(c, log, "lockout-duration-seconds", 300, 0, 31_536_000);
        this.maxGuiCloses = intIn(c, log, "max-gui-closes", 8, 0, 1000);
        this.maxRegistrationAttempts = intIn(c, log, "max-registration-attempts", 5, 0, 1000);
        this.hashIterations = intIn(c, log, "hash-iterations", 210_000, 50_000, 5_000_000);
        this.attemptCooldownMillis = intIn(c, log, "attempt-cooldown-millis", 1500, 0, 60_000);
        this.allowBedrockPlayers = c.getBoolean("allow-bedrock-players", true);
        this.requirePinConfirmation = c.getBoolean("require-pin-confirmation", true);
        this.freezeUnauthenticatedPlayers = c.getBoolean("freeze-unauthenticated-players", true);
        this.reopenGuiOnClose = c.getBoolean("reopen-gui-on-close", true);
        this.blockDuplicateLogin = c.getBoolean("block-duplicate-login", true);
        this.clickSounds = c.getBoolean("click-sounds", true);
        this.showDigits = c.getBoolean("gui.show-digits", false);
        this.cancelButtonEnabled = c.getBoolean("gui.cancel-button-enabled", true);

        String db = c.getString("database-file", "accounts.db");
        if (db == null || !db.matches("[A-Za-z0-9._-]{1,64}") || db.startsWith(".")) {
            log.warning("Invalid database-file name; using accounts.db");
            db = "accounts.db";
        }
        this.databaseFile = db;

        Set<String> allowed = new HashSet<>();
        for (String s : c.getStringList("allowed-commands")) {
            String n = s.trim().toLowerCase(Locale.ROOT);
            if (n.startsWith("/")) n = n.substring(1);
            if (!n.isEmpty()) allowed.add(n);
        }
        this.allowedCommands = Set.copyOf(allowed);

        this.titleLogin = c.getString("gui.titles.login", "&8Kodeshi SMP | Login");
        this.titleRegister = c.getString("gui.titles.register", "&8Kodeshi SMP | Register");
        this.titleChange = c.getString("gui.titles.change", "&8Kodeshi SMP | Change PIN");

        this.fillerMaterial = mat(c, log, "gui.materials.filler", Material.GRAY_STAINED_GLASS_PANE);
        this.infoMaterial = mat(c, log, "gui.materials.info", Material.PLAYER_HEAD);
        this.digitMaterial = mat(c, log, "gui.materials.digit", Material.WHITE_CONCRETE);
        this.clearMaterial = mat(c, log, "gui.materials.clear", Material.RED_CONCRETE);
        this.backspaceMaterial = mat(c, log, "gui.materials.backspace", Material.ORANGE_CONCRETE);
        this.confirmMaterial = mat(c, log, "gui.materials.confirm", Material.LIME_CONCRETE);
        this.cancelMaterial = mat(c, log, "gui.materials.cancel", Material.BARRIER);
        this.pinEmptyMaterial = mat(c, log, "gui.materials.pin-empty", Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        this.pinFilledMaterial = mat(c, log, "gui.materials.pin-filled", Material.LIME_STAINED_GLASS_PANE);

        this.nameClear = c.getString("gui.names.clear", "&c&lCLR");
        this.nameBackspace = c.getString("gui.names.backspace", "&6&lDEL");
        this.nameConfirm = c.getString("gui.names.confirm", "&a&lOK");
        this.nameCancel = c.getString("gui.names.cancel", "&c&lCANCEL");
        this.namePinEmpty = c.getString("gui.names.pin-empty", "&7_");
        this.namePinFilled = c.getString("gui.names.pin-filled", "&a&l*");

        // ---- layout
        int[] pins = DEFAULT_PIN_SLOTS.clone();
        List<Integer> configuredPins = c.getIntegerList("gui.layout.pin-slots");
        if (!configuredPins.isEmpty()) {
            pins = configuredPins.stream().mapToInt(Integer::intValue).toArray();
        }
        int info = c.getInt("gui.layout.info", DEF_INFO);
        int[] digits = new int[10];
        for (int d = 0; d < 10; d++) digits[d] = c.getInt("gui.layout.digits." + d, DEFAULT_DIGITS[d]);
        int clear = c.getInt("gui.layout.clear", DEF_CLEAR);
        int back = c.getInt("gui.layout.backspace", DEF_BACK);
        int confirm = c.getInt("gui.layout.confirm", DEF_CONFIRM);
        int cancel = cancelButtonEnabled ? c.getInt("gui.layout.cancel", DEF_CANCEL) : -1;

        if (!layoutValid(pins, info, digits, clear, back, confirm, cancel)) {
            log.warning("gui.layout is invalid (slots must be unique and within 0-53); using the default layout.");
            pins = DEFAULT_PIN_SLOTS.clone();
            info = DEF_INFO;
            digits = DEFAULT_DIGITS.clone();
            clear = DEF_CLEAR;
            back = DEF_BACK;
            confirm = DEF_CONFIRM;
            cancel = cancelButtonEnabled ? DEF_CANCEL : -1;
        }
        if (maxLen > pins.length) {
            log.warning("max-pin-length (" + maxLen + ") exceeds the number of pin-slots (" + pins.length
                    + "); lowering it.");
            maxLen = pins.length;
            minLen = Math.min(minLen, maxLen);
        }
        this.minPinLength = minLen;
        this.maxPinLength = maxLen;
        this.pinSlots = pins;
        this.infoSlot = info;
        this.digitSlots = digits;
        this.clearSlot = clear;
        this.backspaceSlot = back;
        this.confirmSlot = confirm;
        this.cancelSlot = cancel;

        // ---- text
        Map<String, String> msgs = new HashMap<>();
        ConfigurationSection ms = c.getConfigurationSection("messages");
        if (ms != null) {
            for (String k : ms.getKeys(false)) {
                String v = ms.getString(k);
                if (v != null) msgs.put(k, v);
            }
        }
        this.messages = Map.copyOf(msgs);
        this.prefix = msgs.getOrDefault("prefix", "");

        Map<String, String> pr = new HashMap<>();
        ConfigurationSection ps = c.getConfigurationSection("gui.prompts");
        if (ps != null) {
            for (String k : ps.getKeys(false)) {
                String v = ps.getString(k);
                if (v != null) pr.put(k, v);
            }
        }
        this.prompts = Map.copyOf(pr);
        this.infoLore = List.copyOf(new ArrayList<>(c.getStringList("gui.info-lore")));
    }

    // ---------------------------------------------------------------- accessors

    public long lockoutMillis() {
        return lockoutDurationSeconds * 1000L;
    }

    public int digitForSlot(int slot) {
        for (int d = 0; d < digitSlots.length; d++) {
            if (digitSlots[d] == slot) return d;
        }
        return -1;
    }

    public String lengthDescription() {
        return minPinLength == maxPinLength ? String.valueOf(minPinLength) : minPinLength + "-" + maxPinLength;
    }

    public boolean isCommandAllowed(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        if (allowedCommands.contains(l)) return true;
        return l.startsWith(NAMESPACE) && allowedCommands.contains(l.substring(NAMESPACE.length()));
    }

    public Component title(PinSession.Stage stage) {
        String t = switch (stage) {
            case LOGIN -> titleLogin;
            case REGISTER_ENTER, REGISTER_CONFIRM -> titleRegister;
            default -> titleChange;
        };
        return Text.legacy(t);
    }

    public String prompt(PinSession.Stage stage) {
        return prompts.getOrDefault(stage.key(), stage.name());
    }

    public List<String> infoLore() {
        return infoLore;
    }

    public String raw(String key, String... pairs) {
        String s = messages.getOrDefault(key, "&c[missing message: " + key + "]");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = s.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return s.replace("\\n", "\n");
    }

    /** Chat message with prefix. Placeholders are given as key, value pairs. */
    public Component message(String key, String... pairs) {
        return Text.legacy(prefix + raw(key, pairs));
    }

    /** Message without prefix (kick screens). */
    public Component plain(String key, String... pairs) {
        return Text.legacy(raw(key, pairs));
    }

    // ---------------------------------------------------------------- helpers

    private static int intIn(FileConfiguration c, Logger log, String path, int def, int min, int max) {
        int v = c.getInt(path, def);
        if (v < min || v > max) {
            log.warning("Invalid value " + v + " for '" + path + "' (allowed " + min + "-" + max
                    + "); using " + def + ".");
            return def;
        }
        return v;
    }

    private static Material mat(FileConfiguration c, Logger log, String path, Material def) {
        String name = c.getString(path);
        if (name == null) return def;
        Material m = Material.matchMaterial(name);
        if (m == null || !m.isItem() || m.isAir()) {
            log.warning("Invalid material '" + name + "' at '" + path + "'; using " + def + ".");
            return def;
        }
        return m;
    }

    private static boolean layoutValid(int[] pins, int info, int[] digits, int clear, int back, int confirm,
                                       int cancel) {
        Set<Integer> used = new HashSet<>();
        List<Integer> all = new ArrayList<>();
        for (int p : pins) all.add(p);
        all.add(info);
        for (int d : digits) all.add(d);
        all.add(clear);
        all.add(back);
        all.add(confirm);
        if (cancel >= 0) all.add(cancel);
        for (int slot : all) {
            if (slot < 0 || slot >= GUI_SIZE || !used.add(slot)) return false;
        }
        return true;
    }
}
