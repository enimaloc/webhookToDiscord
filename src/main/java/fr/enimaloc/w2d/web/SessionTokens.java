package fr.enimaloc.w2d.web;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/** Stateless signed session tokens: {@code <expiryEpochSeconds>.<base64url(HMAC-SHA256(secret, expiry))>}. */
public final class SessionTokens {
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final Clock clock;

    public SessionTokens(byte[] secret, Clock clock) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("Session secret must not be empty");
        }
        this.secret = secret.clone();
        this.clock = clock;
    }

    /**
     * Tokens keyed by HMAC-SHA256(sessionSecret, "w2d-admin:" + adminPassword): changing the admin password
     * revokes every issued session. Logout is stateless, so a token otherwise lives until its TTL (12 h).
     */
    public static SessionTokens forAdmin(byte[] sessionSecret, String adminPassword, Clock clock) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(sessionSecret, ALGORITHM));
            byte[] key = mac.doFinal(("w2d-admin:" + adminPassword).getBytes(StandardCharsets.UTF_8));
            return new SessionTokens(key, clock);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String issue(Duration ttl) {
        String expiry = Long.toString(clock.instant().plus(ttl).getEpochSecond());
        return expiry + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(expiry));
    }

    public boolean valid(String token) {
        if (token == null) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return false;
        }
        String expiry = token.substring(0, dot);
        long expirySeconds;
        byte[] signature;
        try {
            expirySeconds = Long.parseLong(expiry);
            signature = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return false;
        }
        // signature first, constant-time
        if (!MessageDigest.isEqual(sign(expiry), signature)) {
            return false;
        }
        return clock.instant().getEpochSecond() < expirySeconds;
    }

    private byte[] sign(String expiry) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(expiry.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
