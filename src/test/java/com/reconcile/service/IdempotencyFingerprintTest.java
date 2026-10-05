package com.reconcile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconcile.service.IdempotencyService.Route;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The idempotency fingerprint, as a pure function.
 *
 * <p>Maps to spec 03 §A2. This is the one part of idempotency that needs no database, no container
 * and no HTTP, so it is tested here in milliseconds. The HTTP tests prove the behaviour end to end;
 * what they cannot do cheaply is show that the property is <i>why</i> it behaves that way.
 *
 * <p>The rule worth protecting is §A2's deliberate inconvenience: two requests differing only in key
 * order or whitespace hash differently, and therefore conflict. A "smarter" canonicalisation would
 * silently treat them as one request and pick one of them — which is how a client ends up retrying
 * a capture and getting a different payment's answer.
 */
class IdempotencyFingerprintTest {

    private static final Route CREATE = IdempotencyService.CREATE_PAYMENT;
    private static final Route CAPTURE = IdempotencyService.CAPTURE_PAYMENT;

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("A2: the same method, template and body produce the same fingerprint")
    void identicalRequestsMatch() {
        String body = "{\"merchantReference\":\"M-1\",\"amount\":{\"amountMinor\":100}}";

        assertThat(IdempotencyService.fingerprint(CREATE, bytes(body)))
                .isEqualTo(IdempotencyService.fingerprint(CREATE, bytes(body)));
    }

    @Test
    @DisplayName("A2: a different body is a different fingerprint, so the key conflicts")
    void differentBodiesDiffer() {
        assertThat(IdempotencyService.fingerprint(CREATE, bytes("{\"merchantReference\":\"A\"}")))
                .isNotEqualTo(IdempotencyService.fingerprint(
                        CREATE, bytes("{\"merchantReference\":\"B\"}")));
    }

    @Test
    @DisplayName("A2: whitespace and key order are not normalised away — the ambiguity is real")
    void reformattingIsNotTreatedAsTheSameRequest() {
        String compact = "{\"merchantReference\":\"M-1\",\"amount\":{\"amountMinor\":100}}";
        String spaced = "{ \"merchantReference\" : \"M-1\", \"amount\" : { \"amountMinor\" : 100 } }";

        assertThat(IdempotencyService.fingerprint(CREATE, bytes(compact)))
                .as("a client that re-serialises differently on retry genuinely does not know which "
                        + "of the two it meant, so it must be told rather than guessed at")
                .isNotEqualTo(IdempotencyService.fingerprint(CREATE, bytes(spaced)));
    }

    @Test
    @DisplayName("A2: a different endpoint is a different fingerprint, so key namespaces stay separate")
    void differentRoutesDiffer() {
        byte[] body = bytes("{}");

        assertThat(IdempotencyService.fingerprint(CREATE, body))
                .isNotEqualTo(IdempotencyService.fingerprint(CAPTURE, body));
    }

    @Test
    @DisplayName("A2: the stored form is a hex SHA-256, which is what char(64) holds exactly")
    void fingerprintFitsTheColumn() {
        String fingerprint = IdempotencyService.fingerprint(CREATE, bytes("{}"));

        assertThat(fingerprint).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("A2: an absent body and an empty one are the same request")
    void nullAndEmptyBodiesAgree() {
        assertThat(IdempotencyService.fingerprint(CREATE, null))
                .as("a key endpoint such as capture may be called with no body at all")
                .isEqualTo(IdempotencyService.fingerprint(CREATE, new byte[0]));
    }
}
