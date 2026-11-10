package fr.enimaloc.w2d.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTokensTest {
    private static final byte[] SECRET = "secret-one".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private static SessionTokens tokens(byte[] secret, Instant now) {
        return new SessionTokens(secret, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issuedTokenIsValid() {
        SessionTokens t = tokens(SECRET, NOW);
        String token = t.issue(Duration.ofHours(12));
        assertThat(token).startsWith(NOW.plus(Duration.ofHours(12)).getEpochSecond() + ".");
        assertThat(t.valid(token)).isTrue();
    }

    @Test
    void expiredTokenIsInvalid() {
        String token = tokens(SECRET, NOW).issue(Duration.ofHours(12));
        assertThat(tokens(SECRET, NOW.plus(Duration.ofHours(12)).minusSeconds(1)).valid(token)).isTrue();
        assertThat(tokens(SECRET, NOW.plus(Duration.ofHours(12))).valid(token)).isFalse();
        assertThat(tokens(SECRET, NOW.plus(Duration.ofDays(1))).valid(token)).isFalse();
    }

    @Test
    void tamperedTokenIsInvalid() {
        SessionTokens t = tokens(SECRET, NOW);
        String token = t.issue(Duration.ofHours(12));
        String sig = token.substring(token.indexOf('.') + 1);
        char flipped = sig.charAt(0) == 'A' ? 'B' : 'A';
        assertThat(t.valid(token.substring(0, token.indexOf('.') + 1) + flipped + sig.substring(1))).isFalse();

        // extending the expiry invalidates the signature
        long expiry = Long.parseLong(token.substring(0, token.indexOf('.')));
        assertThat(t.valid((expiry + 3600) + "." + sig)).isFalse();
    }

    @Test
    void otherSecretIsInvalid() {
        String token = tokens(SECRET, NOW).issue(Duration.ofHours(12));
        assertThat(tokens("secret-two".getBytes(StandardCharsets.UTF_8), NOW).valid(token)).isFalse();
    }

    @Test
    void malformedTokensAreInvalid() {
        SessionTokens t = tokens(SECRET, NOW);
        assertThat(t.valid(null)).isFalse();
        assertThat(t.valid("")).isFalse();
        assertThat(t.valid(".")).isFalse();
        assertThat(t.valid("abc.def")).isFalse();
        assertThat(t.valid("123")).isFalse();
        assertThat(t.valid("123.!!!")).isFalse();
        assertThat(t.valid("1.2.3")).isFalse();
    }
}
