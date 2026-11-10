package fr.enimaloc.w2d.web;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import io.javalin.testtools.JavalinTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Auth behaviour without a database: a dummy protected route stands in for the panel API. */
class AdminAuthTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T12:00:00Z"));
    private final AdminAuth auth = new AdminAuth("hunter2",
            new SessionTokens("k".getBytes(StandardCharsets.UTF_8), clock), clock);

    private Javalin app() {
        return Javalin.create(cfg -> {
            cfg.routes.get("/health", ctx -> ctx.result("ok"));
            cfg.routes.get("/webhook/{id}", ctx -> ctx.result("hook"));
            auth.register(cfg.routes);
            cfg.routes.get("/api/secret", ctx -> ctx.result("secret"));
        });
    }

    private static HttpResponse<String> send(Javalin server, String method, String path, String body,
                                             String... headers) {
        try {
            var b = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            boolean hasContentType = false;
            for (int i = 0; i < headers.length; i += 2) {
                hasContentType |= headers[i].equalsIgnoreCase("Content-Type");
                b.header(headers[i], headers[i + 1]);
            }
            if (!hasContentType) {
                b.header("Content-Type", "application/json");
            }
            return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpResponse<String> login(Javalin server, String password, String... headers) {
        return send(server, "POST", "/api/login", "{\"password\":\"" + password + "\"}", headers);
    }

    private static String sessionCookie(HttpResponse<String> response) {
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    @Test
    void publicRoutesStayPublic() {
        JavalinTest.test(app(), (server, client) -> {
            assertThat(send(server, "GET", "/health", null).statusCode()).isEqualTo(200);
            assertThat(send(server, "GET", "/webhook/x", null).statusCode()).isEqualTo(200);
            assertThat(send(server, "GET", "/api/session", null).body()).contains("\"authenticated\":false");
        });
    }

    @Test
    void guardRejectsWithoutValidCookie() {
        JavalinTest.test(app(), (server, client) -> {
            var r = send(server, "GET", "/api/secret", null);
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(r.body()).doesNotContain("secret");
            assertThat(send(server, "GET", "/api/secret", null, "Cookie", "w2d_session=1.abc").statusCode())
                    .isEqualTo(401);
            assertThat(send(server, "POST", "/api/logout", null).statusCode()).isEqualTo(401);
            for (String sneaky : new String[]{"/api/login/../secret", "/api/session/%2e%2e/secret",
                    "/api/session/..%2fsecret", "//api/secret", "/api//secret", "/API/secret"}) {
                var s = send(server, "GET", sneaky, null);
                assertThat(s.body()).as(sneaky).isNotEqualTo("secret");
            }
        });
    }

    @Test
    void loginSetsHardenedCookieAndGrantsAccess() {
        JavalinTest.test(app(), (server, client) -> {
            var r = login(server, "hunter2");
            assertThat(r.statusCode()).isEqualTo(204);
            String setCookie = r.headers().firstValue("Set-Cookie").orElseThrow();
            assertThat(setCookie).startsWith("w2d_session=").containsIgnoringCase("HttpOnly")
                    .containsIgnoringCase("SameSite=Strict").contains("Path=/")
                    .containsIgnoringCase("Max-Age=43200").doesNotContainIgnoringCase("Secure");
            String cookie = sessionCookie(r);

            var ok = send(server, "GET", "/api/secret", null, "Cookie", cookie);
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.body()).isEqualTo("secret");
            assertThat(send(server, "GET", "/api/session", null, "Cookie", cookie).body())
                    .contains("\"authenticated\":true");

            clock.advance(Duration.ofHours(12));
            assertThat(send(server, "GET", "/api/secret", null, "Cookie", cookie).statusCode()).isEqualTo(401);
        });
    }

    @Test
    void secureCookieBehindHttpsProxy() {
        JavalinTest.test(app(), (server, client) -> {
            var r = login(server, "hunter2", "X-Forwarded-Proto", "https");
            assertThat(r.headers().firstValue("Set-Cookie").orElseThrow()).containsIgnoringCase("Secure");
        });
    }

    @Test
    void logoutExpiresCookie() {
        JavalinTest.test(app(), (server, client) -> {
            String cookie = sessionCookie(login(server, "hunter2"));
            var r = send(server, "POST", "/api/logout", null, "Cookie", cookie);
            assertThat(r.statusCode()).isEqualTo(204);
            assertThat(r.headers().firstValue("Set-Cookie").orElseThrow())
                    .startsWith("w2d_session=;").containsAnyOf("Max-Age=0", "Expires=Thu, 01 Jan 1970");
        });
    }

    @Test
    void wrongPasswordThenThrottledWithExponentialDelay() {
        JavalinTest.test(app(), (server, client) -> {
            assertThat(login(server, "nope").statusCode()).isEqualTo(401);
            // during the delay even the right password is refused without being checked
            var throttled = login(server, "hunter2");
            assertThat(throttled.statusCode()).isEqualTo(429);
            assertThat(throttled.headers().firstValue("Set-Cookie")).isEmpty();

            clock.advance(Duration.ofSeconds(1));
            assertThat(login(server, "nope").statusCode()).isEqualTo(401);
            clock.advance(Duration.ofSeconds(1));
            assertThat(login(server, "hunter2").statusCode()).isEqualTo(429); // 2nd failure => 2 s
            clock.advance(Duration.ofSeconds(1));
            assertThat(login(server, "hunter2").statusCode()).isEqualTo(204);

            // success resets the counter: next failure only costs 1 s again
            assertThat(login(server, "nope").statusCode()).isEqualTo(401);
            clock.advance(Duration.ofSeconds(1));
            assertThat(login(server, "nope").statusCode()).isEqualTo(401);
        });
    }

    @Test
    void delayIsCappedAtSixtySeconds() {
        JavalinTest.test(app(), (server, client) -> {
            for (int i = 0; i < 10; i++) {
                assertThat(login(server, "nope").statusCode()).isEqualTo(401);
                clock.advance(Duration.ofSeconds(60));
            }
            assertThat(login(server, "hunter2").statusCode()).isEqualTo(204);
        });
    }

    @Test
    void throttlingIsPerForwardedClient() {
        JavalinTest.test(app(), (server, client) -> {
            // only the rightmost entry (appended by our proxy) counts; spoofed leftmost values are ignored
            assertThat(login(server, "nope", "X-Forwarded-For", "1.1.1.1, 10.0.0.1").statusCode()).isEqualTo(401);
            assertThat(login(server, "hunter2", "X-Forwarded-For", "2.2.2.2, 10.0.0.1").statusCode()).isEqualTo(429);
            assertThat(login(server, "hunter2", "X-Forwarded-For", "10.0.0.1 ,").statusCode()).isEqualTo(429);
            assertThat(login(server, "hunter2", "X-Forwarded-For", "1.1.1.1, 10.0.0.2").statusCode())
                    .isEqualTo(204);
        });
    }

    @Test
    void failureMapIsBounded() {
        JavalinTest.test(app(), (server, client) -> {
            assertThat(login(server, "nope", "X-Forwarded-For", "10.0.0.1").statusCode()).isEqualTo(401);
            assertThat(auth.trackedClients()).isEqualTo(1);
            clock.advance(Duration.ofHours(1));
            assertThat(login(server, "nope", "X-Forwarded-For", "10.0.0.2").statusCode()).isEqualTo(401);
            assertThat(auth.trackedClients()).isEqualTo(1);
        });
    }

    @Test
    void mutatingApiCallsRequireJsonContentType() {
        JavalinTest.test(app(), (server, client) -> {
            assertThat(login(server, "hunter2", "Content-Type", "text/plain").statusCode()).isEqualTo(415);
            assertThat(login(server, "hunter2", "Content-Type", "application/x-www-form-urlencoded").statusCode())
                    .isEqualTo(415);
            String cookie = sessionCookie(login(server, "hunter2", "Content-Type", "application/json; charset=utf-8"));
            assertThat(send(server, "POST", "/api/logout", null, "Cookie", cookie, "Content-Type", "text/plain")
                    .statusCode()).isEqualTo(415);
            assertThat(send(server, "POST", "/api/logout", null, "Cookie", cookie).statusCode()).isEqualTo(204);
            // reads need no content type
            assertThat(send(server, "GET", "/api/secret", null, "Cookie", cookie, "Content-Type", "text/plain")
                    .statusCode()).isEqualTo(200);
        });
    }

    @Test
    void malformedLoginBodyIs400() {
        JavalinTest.test(app(), (server, client) -> {
            assertThat(send(server, "POST", "/api/login", "not json").statusCode()).isEqualTo(400);
            assertThat(send(server, "POST", "/api/login", "{}").statusCode()).isEqualTo(400);
            assertThat(send(server, "POST", "/api/login", "{\"password\":1}").statusCode()).isEqualTo(400);
        });
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            now = new AtomicReference<>(start);
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
