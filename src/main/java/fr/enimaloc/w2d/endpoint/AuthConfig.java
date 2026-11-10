package fr.enimaloc.w2d.endpoint;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AuthConfig.None.class, name = "none"),
        @JsonSubTypes.Type(value = AuthConfig.SecretHeader.class, name = "secretHeader"),
        @JsonSubTypes.Type(value = AuthConfig.Bearer.class, name = "bearer"),
        @JsonSubTypes.Type(value = AuthConfig.Basic.class, name = "basic"),
        @JsonSubTypes.Type(value = AuthConfig.Hmac.class, name = "hmac")
})
public sealed interface AuthConfig {
    enum Encoding { HEX, BASE64 }

    /** Copy with every secret field set to null. */
    AuthConfig redacted();

    /** Copy where a null/empty secret is taken from {@code previous} when it has the same type. */
    AuthConfig mergeSecretsFrom(AuthConfig previous);

    private static String keep(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    record None() implements AuthConfig {
        public AuthConfig redacted() {
            return this;
        }

        public AuthConfig mergeSecretsFrom(AuthConfig previous) {
            return this;
        }
    }

    record SecretHeader(String header, String value) implements AuthConfig {
        public AuthConfig redacted() {
            return new SecretHeader(header, null);
        }

        public AuthConfig mergeSecretsFrom(AuthConfig previous) {
            return previous instanceof SecretHeader p ? new SecretHeader(header, keep(value, p.value)) : this;
        }
    }

    record Bearer(String token) implements AuthConfig {
        public AuthConfig redacted() {
            return new Bearer(null);
        }

        public AuthConfig mergeSecretsFrom(AuthConfig previous) {
            return previous instanceof Bearer p ? new Bearer(keep(token, p.token)) : this;
        }
    }

    record Basic(String username, String password) implements AuthConfig {
        public AuthConfig redacted() {
            return new Basic(username, null);
        }

        public AuthConfig mergeSecretsFrom(AuthConfig previous) {
            return previous instanceof Basic p ? new Basic(username, keep(password, p.password)) : this;
        }
    }

    record Hmac(String header, String secret, String algorithm, String prefix, Encoding encoding)
            implements AuthConfig {
        public Hmac {
            if (algorithm == null || algorithm.isEmpty()) algorithm = "HmacSHA256";
            if (encoding == null) encoding = Encoding.HEX;
        }

        public AuthConfig redacted() {
            return new Hmac(header, null, algorithm, prefix, encoding);
        }

        public AuthConfig mergeSecretsFrom(AuthConfig previous) {
            return previous instanceof Hmac p
                    ? new Hmac(header, keep(secret, p.secret), algorithm, prefix, encoding) : this;
        }
    }
}
