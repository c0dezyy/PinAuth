package com.kodeshi.pinauth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Validated, immutable snapshot of config.yml. Invalid values fall back to defaults and add a warning. */
public final class Settings {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private static final int[] DEFAULT_DIGIT_SLOTS = {49, 21, 22, 23, 30, 31, 32, 39, 40, 41};
    private static final Map<String, Material> DEFAULT_MATERIALS = new LinkedHashMap<>();

    static {
        DEFAULT_MATERIALS.put("head", Material.PLAYER_HEAD);
        DEFAULT_MATERIALS.put("digit", Material.WHITE_CONCRETE);
        DEFAULT_MATERIALS.put("clear", Material.RED_CONCRETE);
        DEFAULT_MATERIALS.put("delete", Material.ORANGE_CONCRETE);
        DEFAULT_MATERIALS.put("confirm", Material.LIME_CONCRETE);
        DEFAULT_MATERIALS.put("cancel", Material.BARRIER);
        DEFAULT_MATERIALS.put("filler", Material.BLACK_STAINED_GLASS_PANE);
        DEFAULT_MATERIALS.put("pin-empty", Material.GRAY_STAINED_GLASS_PANE);
        DEFAULT_MATERIALS.put("pin-filled", Material.LIME_STAINED_GLASS_PANE);
    }

    public final int minPinLength;
    public final int maxPinLength;
    public final int maxLoginAttempts;
    public final int loginTimeoutSeconds;
    public final int lockoutDurationSeconds;
    public final int attemptCooldownMillis;
    public final int maxGuiCloses;
    public final int maxRegistrationFailures;
    public final int registrationBlockSeconds;
    public final int pbkdf2Iterations;
    public final int guiOpenDelayTicks;
    public final int guiReopenDelayTicks;
    public final boolean allowBedrock;
    public final boolean requireConfirmation;
    public final boolean freezeUnauthenticated;
    public final boolean reopenOnClose;
    public final boolean preventDuplicateLogin;
    public final boolean rejectWeakPins;
    public final boolean usePepper;
    public final boolean ipExemptionsEnabled;
    public final Set<String> allowedCommands;

    public final int slotHead;
    public final int slotPinStart;
    public final int slotClear;
    public final int slotDelete;
    public final int slotConfirm;
    public final int slotCancel;
    public final int[] slotDigits;

    public final List<String> warnings;

    private final Set<String> trustedIps;
    private final Map<String, String> messages;
    private final Map<String, String> titles;
    private final Map<String, Material> materials;

    public Settings(FileConfiguration c) {
        List<String> w = new ArrayList<>();

        int min = intIn(c, "min-pin-length", 6, 4, 8, w);
        int max = intIn(c, "max-pin-length", 6, 4, 8, w);
        if (min > max) {
            w.add("min-pin-length is greater than max-pin-length; using 6 and 6");
            min = 6;
            max = 6;
        }
        this.minPinLength = min;
        this.maxPinLength = max;
        this.maxLoginAttempts = intIn(c, "max-login-attempts", 4, 1, 20, w);
        this.loginTimeoutSeconds = intIn(c, "login-timeout-seconds", 0, 0, 86_400, w);
        this.lockoutDurationSeconds = intIn(c, "lockout-duration-seconds", 300, 0, 2_592_000, w);
        this.attemptCooldownMillis = intIn(c, "attempt-cooldown-millis", 1500, 0, 60_000, w);
        this.maxGuiCloses = intIn(c, "max-gui-closes", 5, 0, 100, w);
        this.maxRegistrationFailures = intIn(c, "max-registration-failures", 5, 1, 50, w);
        this.registrationBlockSeconds = intIn(c, "registration-block-seconds", 300, 0, 86_400, w);
        this.pbkdf2Iterations = intIn(c, "security.pbkdf2-iterations", 310_000, 100_000, 5_000_000, w);
        this.guiOpenDelayTicks = intIn(c, "gui.open-delay-ticks", 10, 1, 200, w);
        this.guiReopenDelayTicks = intIn(c, "gui.reopen-delay-ticks", 2, 1, 200, w);
        this.allowBedrock = c.getBoolean("allow-bedrock-players", true);
        this.requireConfirmation = c.getBoolean("require-pin-confirmation", true);
        this.freezeUnauthenticated = c.getBoolean("freeze-unauthenticated-players", true);
        this.reopenOnClose = c.getBoolean("reopen-gui-on-close", true);
        this.preventDuplicateLogin = c.getBoolean("prevent-duplicate-login", true);
        this.rejectWeakPins = c.getBoolean("reject-weak-pins", false);
        this.usePepper = c.getBoolean("security.use-pepper", true);

        this.ipExemptionsEnabled = c.getBoolean("ip-exemptions.enabled", false);
        boolean allowLoopback = c.getBoolean("ip-exemptions.allow-loopback", false);
        Set<String> ips = new HashSet<>();
        for (String raw : c.getStringList("ip-exemptions.ips")) {
            String ip = raw.trim();
            if (ip.isEmpty()) {
                continue;
            }
            boolean loopback = ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1");
            if (ip.equals("0.0.0.0") || !ip.matches("[0-9a-fA-F:.]+")) {
                w.add("ip-exemptions.ips: '" + raw + "' ignored (not a valid IP address)");
            } else if (loopback && !allowLoopback) {
                w.add("ip-exemptions.ips: '" + raw + "' ignored (loopback exempts everyone connecting through Geyser/a proxy on this machine; "
                        + "set ip-exemptions.allow-loopback: true only if you really want that)");
            } else {
                ips.add(ip);
            }
        }
        this.trustedIps = Collections.unmodifiableSet(ips);

        Set<String> cmds = new HashSet<>();
        for (String s : c.getStringList("allowed-commands")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("/")) {
                t = t.substring(1);
            }
            if (!t.isEmpty()) {
                cmds.add(t);
            }
        }
        this.allowedCommands = Collections.unmodifiableSet(cmds);

        // ---- materials
        Map<String, Material> mats = new LinkedHashMap<>();
        for (Map.Entry<String, Material> e : DEFAULT_MATERIALS.entrySet()) {
            String raw = c.getString("gui.materials." + e.getKey());
            Material m = raw == null ? null : Material.matchMaterial(raw);
            if (m == null || !m.isItem() || m.isAir()) {
                if (raw != null) {
                    w.add("gui.materials." + e.getKey() + " ('" + raw + "') is not a valid item; using "
                            + e.getValue().name());
                }
                m = e.getValue();
            }
            mats.put(e.getKey(), m);
        }
        this.materials = Collections.unmodifiableMap(mats);

        // ---- slots (all-or-nothing: a bad layout falls back to the default layout)
        int head = c.getInt("gui.slots.head", 4);
        int pinStart = c.getInt("gui.slots.pin-start", 10);
        int clear = c.getInt("gui.slots.clear", 48);
        int delete = c.getInt("gui.slots.delete", 50);
        int confirm = c.getInt("gui.slots.confirm", 53);
        int cancel = c.getInt("gui.slots.cancel", 45);
        List<Integer> digitList = c.getIntegerList("gui.slots.digits");
        int[] digits = new int[10];
        boolean layoutOk = digitList.size() == 10;
        if (layoutOk) {
            for (int i = 0; i < 10; i++) {
                digits[i] = digitList.get(i);
            }
            layoutOk = validLayout(head, pinStart, max, clear, delete, confirm, cancel, digits);
        }
        if (!layoutOk) {
            w.add("gui.slots is invalid (need 10 digit slots, all within 0-53, no overlaps); using the default layout");
            head = 4;
            pinStart = 10;
            clear = 48;
            delete = 50;
            confirm = 53;
            cancel = 45;
            digits = DEFAULT_DIGIT_SLOTS.clone();
            if (!validLayout(head, pinStart, max, clear, delete, confirm, cancel, digits)) {
                pinStart = 9; // only reachable for unusual lengths; still collision-free
            }
        }
        this.slotHead = head;
        this.slotPinStart = pinStart;
        this.slotClear = clear;
        this.slotDelete = delete;
        this.slotConfirm = confirm;
        this.slotCancel = cancel;
        this.slotDigits = digits;

        // ---- text
        this.messages = readSection(c, "messages");
        this.titles = readSection(c, "gui.titles");
        for (Flow f : Flow.values()) {
            if (!titles.containsKey(f.key)) {
                w.add("gui.titles." + f.key + " is missing");
            }
        }
        this.warnings = Collections.unmodifiableList(w);
    }

    private static boolean validLayout(int head, int pinStart, int pinLen, int clear, int delete,
                                       int confirm, int cancel, int[] digits) {
        Set<Integer> used = new HashSet<>();
        List<Integer> all = new ArrayList<>();
        all.add(head);
        all.add(clear);
        all.add(delete);
        all.add(confirm);
        all.add(cancel);
        for (int d : digits) {
            all.add(d);
        }
        for (int i = 0; i < pinLen; i++) {
            all.add(pinStart + i);
        }
        for (int s : all) {
            if (s < 0 || s > 53 || !used.add(s)) {
                return false;
            }
        }
        return true;
    }

    private static int intIn(FileConfiguration c, String path, int def, int min, int max, List<String> w) {
        int v = c.getInt(path, def);
        if (v < min || v > max) {
            w.add(path + " must be between " + min + " and " + max + " (got " + v + "); using " + def);
            return def;
        }
        return v;
    }

    private static Map<String, String> readSection(FileConfiguration c, String path) {
        Set<String> keys = new LinkedHashSet<>();
        if (c.getDefaults() != null) {
            ConfigurationSection d = c.getDefaults().getConfigurationSection(path);
            if (d != null) {
                keys.addAll(d.getKeys(false));
            }
        }
        ConfigurationSection cur = c.getConfigurationSection(path);
        if (cur != null) {
            keys.addAll(cur.getKeys(false));
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : keys) {
            String v = c.getString(path + "." + k);
            if (v != null) {
                out.put(k, v);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ accessors

    public Material material(String key) {
        return materials.get(key);
    }

    public Component title(Flow flow) {
        return LEGACY.deserialize(titles.getOrDefault(flow.key, "Kodeshi SMP"));
    }

    /** Raw text with placeholders replaced (key/value pairs); no prefix. */
    public String raw(String key, String... kv) {
        String s = messages.get(key);
        if (s == null) {
            s = "&cMissing message: " + key;
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return s;
    }

    /** Component without prefix (used for GUI items and kick screens). */
    public Component text(String key, String... kv) {
        return LEGACY.deserialize(raw(key, kv));
    }

    /** Component with the chat prefix (used for chat messages). */
    public Component msg(String key, String... kv) {
        return LEGACY.deserialize(messages.getOrDefault("prefix", "") + raw(key, kv));
    }

    /** True if IP exemptions are enabled and this exact IP is listed (the account is irrelevant). */
    public boolean isIpExempt(String ip) {
        return ipExemptionsEnabled && ip != null && trustedIps.contains(ip);
    }

    public String lengthText() {
        return minPinLength == maxPinLength ? Integer.toString(maxPinLength) : minPinLength + "-" + maxPinLength;
    }
}
