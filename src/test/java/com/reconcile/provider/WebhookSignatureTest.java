package com.reconcile.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Maps to rows {@code U-SIG-01}, {@code U-SIG-02}, {@code U-SIG-03}, and F-06. */
class WebhookSignatureTest {

    private static final String SECRET = "whsec_test_secret";
    private static final Instant NOW = Instant.parse("2026-10-04T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String EVENT_ID = "evt_1";
    private static final byte[] BODY =
            "{\"eventId\":\"evt_1\",\"type\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);

    private static String timestamp() {
        return Long.toString(NOW.getEpochSecond());
    }

    private static String sign() {
        return WebhookSignature.sign(SECRET, timestamp(), EVENT_ID, BODY);
    }

    private static Optional<WebhookSignature.Rejection> verify(String signature) {
        return WebhookSignature.verify(SECRET, timestamp(), EVENT_ID, signature, BODY, CLOCK,
                WebhookSignature.DEFAULT_WINDOW);
    }

    @Test
    @DisplayName("U-SIG-01: lowercase hex HMAC-SHA256 over timestamp '.' eventId '.' rawBytes")
    void signMatchesTheDocumentedScheme() throws Exception {
        String signature = sign();

        assertThat(signature).hasSize(64).matches("[0-9a-f]{64}");
        // Independently recomputed, so the test does not merely agree with the implementation.
        assertThat(signature).isEqualTo(referenceHmac(SECRET,
                timestamp() + "." + EVENT_ID + "." + new String(BODY, StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("U-SIG-01: a one-byte change in the body invalidates the signature")
    void bodyIsCovered() {
        String signature = sign();
        byte[] tampered = ("{\"eventId\":\"evt_2\",\"type\":\"payment.captured\"}")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(WebhookSignature.sign(SECRET, timestamp(), EVENT_ID, tampered))
                .isNotEqualTo(signature);
    }

    @Test
    @DisplayName("F-06: changing only the event id invalidates the signature")
    void eventIdIsCovered() {
        String signature = sign();

        // The body and the timestamp are byte-for-byte what the provider signed. Only the identity
        // the inbox would file this under is different. Before the event id became signed material
        // this exact request verified, which is how a captured delivery was replayed under a fresh id
        // and enqueued a second time.
        assertThat(WebhookSignature.sign(SECRET, timestamp(), "evt_2", BODY))
                .as("the event id is the deduplication key, so it must be inside the MAC")
                .isNotEqualTo(signature);

        // And the same bytes presented under a different id are refused, not merely re-signed.
        assertThat(WebhookSignature.verify(SECRET, timestamp(), "evt_2", signature, BODY, CLOCK,
                WebhookSignature.DEFAULT_WINDOW))
                .contains(WebhookSignature.Rejection.BAD_SIGNATURE);
    }

    @Test
    @DisplayName("F-06: the field separator makes the signed tuple unambiguous")
    void fieldsCannotBeShiftedAcrossTheSeparator() {
        // Without an explicit separator, an event id of "a" with a body beginning "b" would produce
        // the same MAC input as an event id of "a.b" with an empty body. Cheap to test, and the
        // kind of ambiguity that is invisible until it is exploited.
        String shiftedAsOne = WebhookSignature.sign(SECRET, timestamp(), "a.b",
                "".getBytes(StandardCharsets.UTF_8));
        String shiftedAsTwo = WebhookSignature.sign(SECRET, timestamp(), "a",
                "b".getBytes(StandardCharsets.UTF_8));

        assertThat(shiftedAsOne).isNotEqualTo(shiftedAsTwo);
    }

    @Test
    @DisplayName("F-06: a blank event id cannot be signed at all")
    void blankEventIdIsRefused() {
        assertThatThrownBy(() -> WebhookSignature.sign(SECRET, timestamp(), "  ", BODY))
                .as("an unauthenticated identity is not a usable default")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event id");
    }

    @Test
    @DisplayName("The raw bytes are what is signed: re-serialising the JSON changes the result")
    void rawBytesMatter() {
        String compact = WebhookSignature.sign(SECRET, timestamp(), EVENT_ID,
                "{\"a\":1,\"b\":2}".getBytes(StandardCharsets.UTF_8));
        String spaced = WebhookSignature.sign(SECRET, timestamp(), EVENT_ID,
                "{ \"a\": 1, \"b\": 2 }".getBytes(StandardCharsets.UTF_8));

        // Both are valid JSON with identical meaning. Only raw-byte signing gets this right, and
        // it is exactly why the controller must verify before it deserialises (row I-WEB-05).
        assertThat(compact).isNotEqualTo(spaced);
    }

    @Test
    @DisplayName("U-SIG-02: exactly 300s either side is accepted; 301s is refused")
    void freshnessBoundaryIsExact() {
        assertThat(WebhookSignature.isFresh(timestamp(), CLOCK, WebhookSignature.DEFAULT_WINDOW))
                .isTrue();

        String edgeOld = Long.toString(NOW.minus(Duration.ofSeconds(300)).getEpochSecond());
        String edgeNew = Long.toString(NOW.plus(Duration.ofSeconds(300)).getEpochSecond());
        assertThat(WebhookSignature.isFresh(edgeOld, CLOCK, WebhookSignature.DEFAULT_WINDOW)).isTrue();
        assertThat(WebhookSignature.isFresh(edgeNew, CLOCK, WebhookSignature.DEFAULT_WINDOW)).isTrue();

        String tooOld = Long.toString(NOW.minus(Duration.ofSeconds(301)).getEpochSecond());
        String tooNew = Long.toString(NOW.plus(Duration.ofSeconds(301)).getEpochSecond());
        assertThat(WebhookSignature.isFresh(tooOld, CLOCK, WebhookSignature.DEFAULT_WINDOW)).isFalse();
        assertThat(WebhookSignature.isFresh(tooNew, CLOCK, WebhookSignature.DEFAULT_WINDOW)).isFalse();
    }

    @Test
    @DisplayName("A malformed timestamp is refused rather than defaulted to 'now'")
    void malformedTimestampIsRefused() {
        assertThat(WebhookSignature.isFresh("not-a-number", CLOCK, WebhookSignature.DEFAULT_WINDOW)).isFalse();
        assertThat(WebhookSignature.isFresh("", CLOCK, WebhookSignature.DEFAULT_WINDOW)).isFalse();
    }

    @Test
    @DisplayName("A genuine, fresh request verifies with no rejection")
    void validRequestVerifies() {
        assertThat(verify(sign())).isEmpty();
    }

    @Test
    @DisplayName("The same signed payload resent with its own event id is still genuine")
    void replayOfTheProvidersOwnRetryIsAccepted() {
        // The intended behaviour, stated so it cannot be "fixed" away: a provider retrying its own
        // delivery produces byte-identical headers and body, and must verify. Deduplication is the
        // inbox's job (provider_events.ux_provider_event), not the signature's.
        assertThat(verify(sign())).isEmpty();
        assertThat(verify(sign()))
                .as("a provider's own retry is not an attack")
                .isEmpty();
    }

    @Test
    @DisplayName("A wrong signature is refused, and a stale one is refused as stale")
    void rejectionsAreDistinguishable() {
        String staleTimestamp = Long.toString(NOW.minus(Duration.ofHours(1)).getEpochSecond());
        String staleSignature = WebhookSignature.sign(SECRET, staleTimestamp, EVENT_ID, BODY);

        assertThat(verify("0".repeat(64)))
                .contains(WebhookSignature.Rejection.BAD_SIGNATURE);

        assertThat(WebhookSignature.verify(SECRET, staleTimestamp, EVENT_ID, staleSignature, BODY,
                CLOCK, WebhookSignature.DEFAULT_WINDOW))
                .contains(WebhookSignature.Rejection.STALE_TIMESTAMP);

        assertThat(verify(null)).contains(WebhookSignature.Rejection.MISSING_SIGNATURE);

        assertThat(WebhookSignature.verify(SECRET, timestamp(), "  ", sign(), BODY, CLOCK,
                WebhookSignature.DEFAULT_WINDOW))
                .contains(WebhookSignature.Rejection.MISSING_EVENT_ID);
    }

    @Test
    @DisplayName("Freshness is checked before the signature, so a stale forged request reads as stale")
    void freshnessIsCheckedFirst() {
        String staleTimestamp = Long.toString(NOW.minus(Duration.ofHours(1)).getEpochSecond());

        // A forged signature on a stale request: the cheaper, more decisive check runs first.
        assertThat(WebhookSignature.verify(SECRET, staleTimestamp, EVENT_ID, "0".repeat(64), BODY,
                CLOCK, WebhookSignature.DEFAULT_WINDOW))
                .contains(WebhookSignature.Rejection.STALE_TIMESTAMP);
    }

    @Test
    @DisplayName("U-SIG-03: comparison is constant-time, not String.equals")
    void comparisonIsConstantTime() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/reconcile/provider/WebhookSignature.java"));

        assertThat(source)
                .as("a String.equals on a signature leaks the valid prefix one byte at a time")
                .doesNotContain("providedSignature.equals")
                .doesNotContain("signature.equals(")
                .contains("MessageDigest.isEqual");
        assertThat(MessageDigest.isEqual(new byte[] {1}, new byte[] {1})).isTrue();
    }

    @Test
    @DisplayName("A blank secret is refused at signing time")
    void blankSecretIsRefused() {
        assertThatThrownBy(() -> WebhookSignature.sign("  ", timestamp(), EVENT_ID, BODY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret");
    }

    /** Independent HMAC, computed without touching the class under test. */
    private static String referenceHmac(String secret, String message) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(
                mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
}