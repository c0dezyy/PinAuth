package com.kodeshi.pinauth;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * PBKDF2-HMAC-SHA256 PIN hashing with a unique 128-bit salt per PIN and an optional
 * server-side secret ("pepper") that is mixed in with HMAC-SHA256 before key stretching.
 *
 * Stored format: {@code alg$iterations$saltBase64$hashBase64}
 *   alg = pbkdf2-sha256   (no pepper)
 *   alg = pbkdf2p-sha256  (pepper applied)
 */
public final class PinHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALG_PLAIN = "pbkdf2-sha256";
    private static final String ALG_PEPPER = "pbkdf2p-sha256";

    private final byte[] pepper;

    public PinHasher(byte[] pepper) {
        this.pepper = pepper == null ? null : pepper.clone();
    }

    /** Loads the pepper file, creating it with 32 random bytes on first run. Never overwrites a bad file. */
    public static byte[] loadOrCreatePepper(File file) throws IOException {
        if (file.isFile()) {
            byte[] existing = Files.readAllBytes(file.toPath());
            if (existing.length < 32) {
                throw new IOException(file.getName() + " is too short/corrupt; refusing to overwrite it");
            }
            return existing;
        }
        byte[] fresh = new byte[32];
        RANDOM.nextBytes(fresh);
        Files.write(file.toPath(), fresh, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // non-POSIX file system (Windows): best effort only
        }
        return fresh;
    }

    public String hash(String pin, int iterations, boolean usePepper) {
        if (usePepper && pepper == null) {
            throw new IllegalStateException("pepper requested but not loaded");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        try {
            byte[] dk = derive(pin, salt, iterations, usePepper);
            Base64.Encoder enc = Base64.getEncoder().withoutPadding();
            return (usePepper ? ALG_PEPPER : ALG_PLAIN) + "$" + iterations + "$"
                    + enc.encodeToString(salt) + "$" + enc.encodeToString(dk);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PIN hashing failed", e);
        }
    }

    public boolean verify(String pin, String stored) {
        if (pin == null || stored == null) {
            return false;
        }
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            boolean peppered;
            if (ALG_PEPPER.equals(parts[0])) {
                peppered = true;
            } else if (ALG_PLAIN.equals(parts[0])) {
                peppered = false;
            } else {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 1000 || iterations > 20_000_000) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(pin, salt, iterations, peppered);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] derive(String pin, byte[] salt, int iterations, boolean usePepper)
            throws GeneralSecurityException {
        char[] password;
        if (usePepper) {
            if (pepper == null) {
                throw new GeneralSecurityException("pepper not available");
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            password = HexFormat.of().formatHex(mac.doFinal(pin.getBytes(StandardCharsets.UTF_8))).toCharArray();
        } else {
            password = pin.toCharArray();
        }
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
            Arrays.fill(password, '\0');
        }
    }
}
