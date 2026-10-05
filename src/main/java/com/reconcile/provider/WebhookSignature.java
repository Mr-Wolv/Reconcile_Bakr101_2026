package com.reconcile.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verification of provider webhook signatures.
 *
 * <p>Scheme: {@code lowercase_hex(HMAC_SHA256(secret, timestamp + "." + eventId + "." + rawBody))}.
 *
 * <p>Four details matter, and each one is a way the obvious implementation is subtly wrong:
 *
 * <ol>
 *   <li><b>The signature covers the raw request bytes.</b> Deserialising to an object and
 *       re-serialising before verifying means any difference in key order, whitespace or number
 *       formatting produces a mismatch on a request the provider considers valid. The raw bytes are
 *       the contract.</li>
 *   <li><b>The signature covers the event id.</b> Not as an unverified header that happens to sit
 *       next to the signature, but as part of the signed material. This was F-06: the event id is
 *       the key the inbox deduplicates on, so authenticating the body while trusting an unsigned
 *       header left the identity unauthenticated. Replaying a captured request under a fresh id
 *       within the freshness window enqueued it a second time. Any keyed MAC scheme needs an
 *       unambiguous separator between the fields being signed, which is what the {@code '.'}
 *       characters are for — without them an event id of {@code "a.b"} and one of {@code "a"} with
 *       a body starting {@code "b"} would produce the same MAC input.</li>
 *   <li><b>Comparison is constant-time.</b> {@link MessageDigest#isEqual} rather than
 *       {@code String.equals}, which returns at the first differing byte and leaks the prefix of a
 *       valid signature one byte at a time.</li>
 *   <li><b>Freshness is checked before the signature.</b> The window bounds replay of a captured
 *       request regardless of whether the attacker holds the key, and it rejects stale requests
 *       without needing to tell them apart from forged ones.</li>
 * </ol>
 *
 * <p><b>Documented replay semantics.</b> Within the freshness window the signature is valid, so a
 * provider retrying its own delivery is accepted and deduplicated by {@code (provider, eventId)} —
 * that is the intended and tested behaviour. What is no longer possible is replaying that same
 * request under a <i>different</i> event id, because the id is part of what was signed. Outside the
 * window, every request is refused regardless of signature.
 */
public final class WebhookSignature {

    /** Accepted clock skew either side of the provider's timestamp. */
    public static final Duration DEFAULT_WINDOW = Duration.ofSeconds(300);

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private WebhookSignature() {
    }

    /**
     * Computes the signature a provider would send.
     *
     * @param eventId the provider's event id, which is part of the signed material and must be the
     *                same value sent in the {@code X-Provider-Event-Id} header
     */
    public static String sign(String secret, String timestamp, String eventId, byte[] rawBody) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("webhook secret must not be blank");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("event id must not be blank: it is signed material");
        }
        Mac mac = macFor(secret);
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '.');
        mac.update(eventId.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '.');
        return HexFormat.of().formatHex(mac.doFinal(rawBody == null ? new byte[0] : rawBody));
    }

    /** Whether the timestamp is inside the freshness window around {@code now}. */
    public static boolean isFresh(String timestamp, Clock clock, Duration window) {
        Instant received;
        try {
            received = Instant.ofEpochSecond(Long.parseLong(timestamp.trim()));
        } catch (RuntimeException e) {
            return false;
        }
        Duration age = Duration.between(received, clock.instant()).abs();
        return age.compareTo(window) <= 0;
    }

    /**
     * Full verification: freshness first, then the signature, both in constant time.
     *
     * @return the reason for rejection, or empty when the request is genuine and fresh
     */
    public static java.util.Optional<Rejection> verify(
            String secret,
            String timestamp,
            String eventId,
            String providedSignature,
            byte[] rawBody,
            Clock clock,
            Duration window) {

        if (timestamp == null || timestamp.isBlank()) {
            return java.util.Optional.of(Rejection.MISSING_TIMESTAMP);
        }
        if (eventId == null || eventId.isBlank()) {
            return java.util.Optional.of(Rejection.MISSING_EVENT_ID);
        }
        if (providedSignature == null || providedSignature.isBlank()) {
            return java.util.Optional.of(Rejection.MISSING_SIGNATURE);
        }
        if (!isFresh(timestamp, clock, window)) {
            return java.util.Optional.of(Rejection.STALE_TIMESTAMP);
        }

        byte[] expected = sign(secret, timestamp, eventId, rawBody)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] provided = providedSignature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, provided)) {
            return java.util.Optional.of(Rejection.BAD_SIGNATURE);
        }
        return java.util.Optional.empty();
    }

    /** Why a webhook was refused. Mapped to HTTP status by the API layer. */
    public enum Rejection {
        MISSING_TIMESTAMP,
        MISSING_SIGNATURE,
        MISSING_EVENT_ID,
        STALE_TIMESTAMP,
        BAD_SIGNATURE
    }

    private static Mac macFor(String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac;
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is required but unavailable", e);
        }
    }
}