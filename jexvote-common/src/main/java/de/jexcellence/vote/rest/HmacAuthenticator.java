package de.jexcellence.vote.rest;

import org.jetbrains.annotations.NotNull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * HMAC-SHA256 request authenticator. Verifies that the caller possesses
 * the shared secret by comparing the provided signature against the
 * expected digest of {@code timestamp + "." + path}.
 *
 * <p>Expected headers on every request:
 * <ul>
 *   <li>{@code X-Signature} — hex-encoded HMAC of {@code <timestamp>.<path>}</li>
 *   <li>{@code X-Timestamp} — Unix epoch seconds when the request was signed</li>
 * </ul>
 *
 * <p>Requests older than {@link #MAX_AGE_SECONDS} are rejected to
 * prevent replay attacks. Mirrors JExOneblock's authenticator so a single
 * shared secret works across both plugin APIs.
 */
final class HmacAuthenticator {

    static final String HEADER_SIGNATURE = "X-Signature";
    static final String HEADER_TIMESTAMP = "X-Timestamp";
    static final long MAX_AGE_SECONDS = 300; // 5 minutes

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    HmacAuthenticator(@NotNull String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    @NotNull AuthResult verify(@NotNull String signature, long timestamp, @NotNull String path) {
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - timestamp) > MAX_AGE_SECONDS) {
            return AuthResult.TIMESTAMP_EXPIRED;
        }

        String payload = timestamp + "." + path;
        String expected = sign(payload.getBytes(StandardCharsets.UTF_8));

        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return AuthResult.INVALID_SIGNATURE;
        }
        return AuthResult.OK;
    }

    private @NotNull String sign(byte @NotNull [] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC init failed", ex);
        }
    }

    enum AuthResult {
        OK,
        TIMESTAMP_EXPIRED,
        INVALID_SIGNATURE
    }
}
