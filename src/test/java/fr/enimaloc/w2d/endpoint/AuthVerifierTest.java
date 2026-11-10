package fr.enimaloc.w2d.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.w2d.endpoint.AuthConfig.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AuthVerifierTest {
    private static final byte[] BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "It's a Secret to Everybody";
    private static final String GH_SIG = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

    private static Function<String, String> headers(String... kv) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i].toLowerCase(), kv[i + 1]);
        return n -> m.get(n.toLowerCase());
    }

    private static boolean verify(AuthConfig a, Function<String, String> h) {
        return AuthVerifier.verify(a, h, BODY);
    }

    @Test
    void none() {
        assertThat(verify(new None(), headers())).isTrue();
    }

    @Test
    void secretHeader() {
        var a = new SecretHeader("X-Token", "v");
        assertThat(verify(a, headers("X-Token", "v"))).isTrue();
        assertThat(verify(a, headers("X-Token", "w"))).isFalse();
        assertThat(verify(a, headers())).isFalse();
        assertThat(verify(new SecretHeader("X-Token", null), headers("X-Token", "v"))).isFalse();
    }

    @Test
    void bearer() {
        var a = new Bearer("tok");
        assertThat(verify(a, headers("Authorization", "Bearer tok"))).isTrue();
        assertThat(verify(a, headers("Authorization", "Bearer nope"))).isFalse();
        assertThat(verify(a, headers())).isFalse();
        assertThat(verify(new Bearer(null), headers("Authorization", "Bearer null"))).isFalse();
    }

    @Test
    void blankSecretsAreRejected() {
        assertThat(verify(new SecretHeader("X-Token", ""), headers("X-Token", ""))).isFalse();
        assertThat(verify(new SecretHeader("X-Token", "  "), headers("X-Token", "  "))).isFalse();
        assertThat(verify(new Bearer(""), headers("Authorization", "Bearer "))).isFalse();
        assertThat(verify(new Bearer(" "), headers("Authorization", "Bearer  "))).isFalse();
        String blank = "Basic " + Base64.getEncoder().encodeToString(":".getBytes(StandardCharsets.UTF_8));
        assertThat(verify(new Basic("", ""), headers("Authorization", blank))).isFalse();
        assertThat(verify(new Hmac("X-Sig", "", "HmacSHA256", null, Encoding.HEX), headers("X-Sig", "00"))).isFalse();
    }

    @Test
    void basic() {
        var a = new Basic("u", "p");
        String ok = "Basic " + Base64.getEncoder().encodeToString("u:p".getBytes(StandardCharsets.UTF_8));
        String bad = "Basic " + Base64.getEncoder().encodeToString("u:x".getBytes(StandardCharsets.UTF_8));
        assertThat(verify(a, headers("Authorization", ok))).isTrue();
        assertThat(verify(a, headers("Authorization", bad))).isFalse();
        assertThat(verify(a, headers())).isFalse();
        assertThat(verify(new Basic("u", null), headers("Authorization", ok))).isFalse();
    }

    @Test
    void hmacHex() {
        var a = new Hmac("X-Hub-Signature-256", SECRET, "HmacSHA256", "sha256=", Encoding.HEX);
        assertThat(verify(a, headers("X-Hub-Signature-256", GH_SIG))).isTrue();
        assertThat(verify(a, headers("X-Hub-Signature-256", GH_SIG.toUpperCase().replace("SHA256=", "sha256="))))
                .isTrue();
        assertThat(verify(a, headers("X-Hub-Signature-256", GH_SIG.substring(0, GH_SIG.length() - 1) + "8")))
                .isFalse();
        assertThat(verify(a, headers("X-Hub-Signature-256", GH_SIG.substring(7)))).isFalse();
        assertThat(verify(a, headers())).isFalse();
        assertThat(verify(a.redacted(), headers("X-Hub-Signature-256", GH_SIG))).isFalse();
    }

    @Test
    void hmacBase64NoPrefix() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = Base64.getEncoder().encodeToString(mac.doFinal(BODY));
        var a = new Hmac("X-Sig", SECRET, "HmacSHA256", null, Encoding.BASE64);
        assertThat(verify(a, headers("X-Sig", sig))).isTrue();
        assertThat(verify(a, headers("X-Sig", "!!notbase64"))).isFalse();
        assertThat(verify(a, headers())).isFalse();
    }

    @Test
    void hmacUnknownAlgorithmRejects() {
        var a = new Hmac("X-Sig", SECRET, "HmacNope", null, Encoding.HEX);
        assertThat(verify(a, headers("X-Sig", "00"))).isFalse();
    }

    @Test
    void maskedHeaders() {
        assertThat(AuthVerifier.maskedHeaders(new SecretHeader("X-Token", "v"))).isEqualTo(Set.of("x-token"));
        assertThat(AuthVerifier.maskedHeaders(new Hmac("X-Sig", "s", null, null, null))).isEqualTo(Set.of("x-sig"));
        assertThat(AuthVerifier.maskedHeaders(new None())).isEmpty();
        assertThat(AuthVerifier.maskedHeaders(new Bearer("t"))).isEmpty();
    }
}
