package fr.enimaloc.w2d.endpoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class AuthVerifier {
    private AuthVerifier() {}

    /** {@code header} returns the first value of a header (case-insensitive) or null. */
    public static boolean verify(AuthConfig auth, Function<String, String> header, byte[] rawBody) {
        try {
            return switch (auth) {
                case AuthConfig.None n -> true;
                case AuthConfig.SecretHeader s -> s.header() != null && !s.header().isBlank() && s.value() != null && !s.value().isBlank()
                        && eq(header.apply(s.header()), s.value());
                case AuthConfig.Bearer b -> b.token() != null && !b.token().isBlank()
                        && eq(header.apply("Authorization"), "Bearer " + b.token());
                case AuthConfig.Basic b -> b.username() != null && b.password() != null
                        && !(blank(b.username()) && blank(b.password()))
                        && eq(header.apply("Authorization"), "Basic " + Base64.getEncoder().encodeToString(
                                (b.username() + ":" + b.password()).getBytes(StandardCharsets.UTF_8)));
                case AuthConfig.Hmac h -> hmac(h, header, rawBody);
            };
        } catch (Exception e) {
            return false;
        }
    }

    public static Set<String> maskedHeaders(AuthConfig auth) {
        return switch (auth) {
            case AuthConfig.SecretHeader s when s.header() != null -> Set.of(s.header().toLowerCase(Locale.ROOT));
            case AuthConfig.Hmac h when h.header() != null -> Set.of(h.header().toLowerCase(Locale.ROOT));
            default -> Set.of();
        };
    }

    private static boolean hmac(AuthConfig.Hmac h, Function<String, String> header, byte[] body) throws Exception {
        if (blank(h.header()) || blank(h.secret())) return false;
        String value = header.apply(h.header());
        if (value == null) return false;
        String prefix = h.prefix();
        if (prefix != null && !prefix.isEmpty()) {
            if (!value.startsWith(prefix)) return false;
            value = value.substring(prefix.length());
        }
        Mac mac = Mac.getInstance(h.algorithm());
        mac.init(new SecretKeySpec(h.secret().getBytes(StandardCharsets.UTF_8), h.algorithm()));
        byte[] expected = mac.doFinal(body);
        byte[] actual = h.encoding() == AuthConfig.Encoding.BASE64
                ? Base64.getDecoder().decode(value)
                : HexFormat.of().parseHex(value.toLowerCase(Locale.ROOT));
        return MessageDigest.isEqual(expected, actual);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean eq(String actual, String expected) {
        if (actual == null) return false;
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
