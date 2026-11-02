package edu.arena.server.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random credentials have high entropy; a fast hash is appropriate here, unlike passwords. */
final class SecretMaterial {
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretMaterial() {}

    static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean validToken(String token) {
        return token != null && token.length() == 43 && token.matches("[A-Za-z0-9_-]{43}");
    }

    static String hash(String token) {
        return HexFormat.of().formatHex(digest(token.getBytes(StandardCharsets.US_ASCII)));
    }

    static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
