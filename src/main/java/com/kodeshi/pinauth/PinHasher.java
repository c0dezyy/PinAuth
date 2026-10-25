package com.kodeshi.pinauth;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * PIN hashing: HMAC-SHA256 with a server-side secret ("pepper", stored in secret.key),
 * then PBKDF2-HMAC-SHA256 with a unique random 16-byte salt per PIN.
 * Stored format: pbkdf2-sha256$iterations$saltBase64$hashBase64
 */
public final class PinHasher {
    private static final String PREFIX = "pbkdf2-sha256";
    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private final byte[] pepper;
    private final SecureRandom random = new SecureRandom();
    private volatile int iterations;

    public PinHasher(byte[] pepper, int iterations) {
        this.pepper = pepper.clone();
        this.iterations = iterations;
    }

    public void setIterations(int iterations) {
        this.iterations = iterations;
    }

    public String hash(char[] pin) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        int it = iterations;
        byte[] dk = derive(pin, salt, it);
        return PREFIX + "$" + it + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(dk);
    }

    public boolean verify(char[] pin, String stored) {
        if (stored == null) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;
        try {
            int it = Integer.parseInt(parts[1]);
            if (it < 1000 || it > 10_000_000) return false;
            byte[] salt = B64D.decode(parts[2]);
            byte[] expected = B64D.decode(parts[3]);
            byte[] actual = derive(pin, salt, it);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] derive(char[] pin, byte[] salt, int it) {
        char[] password = null;
        PBEKeySpec spec = null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            byte[] pre = mac.doFinal(new String(pin).getBytes(StandardCharsets.UTF_8));
            password = B64.encodeToString(pre).toCharArray();
            spec = new PBEKeySpec(password, salt, it, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Required crypto algorithm unavailable", e);
        } finally {
            if (spec != null) spec.clearPassword();
            if (password != null) Arrays.fill(password, '\0');
        }
    }
}
