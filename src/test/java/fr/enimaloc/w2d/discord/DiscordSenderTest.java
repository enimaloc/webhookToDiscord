package fr.enimaloc.w2d.discord;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.w2d.script.OutgoingMessage;
import io.javalin.Javalin;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import fr.enimaloc.w2d.json.Json;

class DiscordSenderTest {
    private static final String TOKEN = "secretTok";
    private static final String URL = "https://discord.com/api/webhooks/1/" + TOKEN;

    private Javalin server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> contentTypes = new CopyOnWriteArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private DiscordSender sender(IntFunction<Reply> script) {
        server = Javalin.create(cfg -> cfg.routes.post("/api/webhooks/1/" + TOKEN, ctx -> {
            bodies.add(ctx.body());
            contentTypes.add(ctx.header("Content-Type"));
            Reply r = script.apply(calls.incrementAndGet());
            ctx.status(r.status);
            if (r.body != null) {
                ctx.result(r.body);
            }
        }));
        server.start(0);
        String base = "http://localhost:" + server.port();
        return new DiscordSender(HttpClient.newHttpClient(), sleeps::add,
                u -> u.replace("https://discord.com", base));
    }

    private record Reply(int status, String body) {
    }

    private OutgoingMessage msg() {
        return new OutgoingMessage(URL, Json.MAPPER.createObjectNode().put("content", "hi"));
    }

    @Test
    void postsJson() {
        DiscordSender s = sender(n -> new Reply(204, null));
        SendResult r = s.send(msg());
        assertThat(r.ok()).isTrue();
        assertThat(r.attempts()).isEqualTo(1);
        assertThat(bodies).containsExactly("{\"content\":\"hi\"}");
        assertThat(contentTypes.get(0)).startsWith("application/json");
    }

    @Test
    void retriesOn429WithRetryAfter() {
        DiscordSender s = sender(n -> n == 1 ? new Reply(429, "{\"retry_after\":0.25}") : new Reply(204, null));
        SendResult r = s.send(msg());
        assertThat(r.ok()).isTrue();
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(sleeps).containsExactly(Duration.ofMillis(250));
    }

    @Test
    void givesUpAfterThreeRetries() {
        DiscordSender s = sender(n -> new Reply(500, "boom"));
        SendResult r = s.send(msg());
        assertThat(r.ok()).isFalse();
        assertThat(r.attempts()).isEqualTo(4);
        assertThat(r.status()).isEqualTo(500);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void noRetryOn400() {
        DiscordSender s = sender(n -> new Reply(400, "bad"));
        SendResult r = s.send(msg());
        assertThat(r.ok()).isFalse();
        assertThat(r.attempts()).isEqualTo(1);
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    void resultUrlIsMasked() {
        DiscordSender s = sender(n -> new Reply(204, null));
        SendResult r = s.send(msg());
        assertThat(r.url()).doesNotContain(TOKEN).isEqualTo("https://discord.com/api/webhooks/1/***");
    }

    @Test
    void sendAllKeepsOrder() {
        DiscordSender s = sender(n -> new Reply(204, null));
        List<SendResult> rs = s.sendAll(List.of(msg("a"), msg("b"), msg("c")));
        assertThat(rs).hasSize(3);
        assertThat(bodies).containsExactly("{\"content\":\"a\"}", "{\"content\":\"b\"}", "{\"content\":\"c\"}");
    }

    private OutgoingMessage msg(String content) {
        return new OutgoingMessage(URL, Json.MAPPER.createObjectNode().put("content", content));
    }

    @Test
    void clampsNegativeRetryAfter() {
        DiscordSender s = sender(n -> n == 1 ? new Reply(429, "{\"retry_after\":-5}") : new Reply(204, null));
        assertThat(s.send(msg()).ok()).isTrue();
        assertThat(sleeps).containsExactly(Duration.ZERO);
    }

    @Test
    void clampsHugeRetryAfter() {
        DiscordSender s = sender(n -> n == 1 ? new Reply(429, "{\"retry_after\":9999}") : new Reply(204, null));
        assertThat(s.send(msg()).ok()).isTrue();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(60));
    }

    @Test
    void timesOutAndRetries() {
        server = Javalin.create(cfg -> cfg.routes.post("/api/webhooks/1/" + TOKEN, ctx -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ctx.status(204);
        }));
        server.start(0);
        String base = "http://localhost:" + server.port();
        DiscordSender s = new DiscordSender(HttpClient.newHttpClient(), sleeps::add,
                u -> u.replace("https://discord.com", base), Duration.ofMillis(100));
        SendResult r = s.send(msg());
        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(0);
        assertThat(r.attempts()).isEqualTo(4);
        assertThat(r.response()).doesNotContain(TOKEN);
    }

    @Test
    void retriesOnNetworkError() throws Exception {
        int port;
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        DiscordSender s = new DiscordSender(HttpClient.newHttpClient(), sleeps::add,
                u -> u.replace("https://discord.com", "http://localhost:" + port));
        SendResult r = s.send(msg());
        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(0);
        assertThat(r.attempts()).isEqualTo(4);
        assertThat(sleeps).hasSize(3);
        assertThat(r.response()).doesNotContain(TOKEN);
    }

    @Test
    void invalidUriDoesNotLeakToken() {
        String bad = "https://discord.com/api/webhooks/1/" + TOKEN + "?a=b c";
        assertThat(DiscordUrls.isValid(bad)).isTrue();
        DiscordSender s = new DiscordSender(HttpClient.newHttpClient(), sleeps::add);
        SendResult r = s.send(new OutgoingMessage(bad, Json.MAPPER.createObjectNode().put("content", "x")));
        assertThat(r.ok()).isFalse();
        assertThat(r.response()).doesNotContain(TOKEN);
        assertThat(r.url()).doesNotContain(TOKEN);
    }

    private record Seen(String method, String query, String body, String contentType) {
    }

    private DiscordSender recordingSender(List<Seen> seen, int status, String reply) {
        String path = "/api/webhooks/1/" + TOKEN;
        server = Javalin.create(cfg -> {
            cfg.routes.patch(path + "/messages/42", ctx -> {
                seen.add(new Seen("PATCH", ctx.queryString(), ctx.body(), ctx.header("Content-Type")));
                ctx.status(status).result(reply);
            });
            cfg.routes.delete(path + "/messages/42", ctx -> {
                seen.add(new Seen("DELETE", ctx.queryString(), ctx.body(), ctx.header("Content-Type")));
                ctx.status(204);
            });
            cfg.routes.post(path, ctx -> {
                seen.add(new Seen("POST", ctx.queryString(), ctx.body(), ctx.header("Content-Type")));
                ctx.status(status).result(reply);
            });
        });
        server.start(0);
        String base = "http://localhost:" + server.port();
        return new DiscordSender(HttpClient.newHttpClient(), sleeps::add, u -> u.replace("https://discord.com", base));
    }

    @Test
    void waitTrueReturnsTheMessageObject() {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        DiscordSender s = recordingSender(seen, 200, "{\"id\":\"42\",\"content\":\"hi\"}");
        SendResult r = s.send(new OutgoingMessage("POST", URL + "?wait=true",
                Json.MAPPER.createObjectNode().put("content", "hi")));
        assertThat(r.ok()).isTrue();
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.message().path("id").asText()).isEqualTo("42");
        assertThat(seen.get(0).query()).isEqualTo("wait=true");
    }

    @Test
    void patchSendsTheBodyWithPatch() {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        DiscordSender s = recordingSender(seen, 200, "{\"id\":\"42\",\"content\":\"edited\"}");
        SendResult r = s.send(new OutgoingMessage("PATCH", URL + "/messages/42",
                Json.MAPPER.createObjectNode().put("content", "edited")));
        assertThat(r.ok()).isTrue();
        assertThat(seen).containsExactly(new Seen("PATCH", null, "{\"content\":\"edited\"}", "application/json"));
        assertThat(r.message().path("content").asText()).isEqualTo("edited");
        assertThat(r.url()).isEqualTo("https://discord.com/api/webhooks/1/***/messages/42");
    }

    @Test
    void deleteSendsNoBody() {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        DiscordSender s = recordingSender(seen, 200, "");
        SendResult r = s.send(new OutgoingMessage("DELETE", URL + "/messages/42", null));
        assertThat(r.ok()).isTrue();
        assertThat(r.status()).isEqualTo(204);
        assertThat(r.message()).isNull();
        assertThat(seen.get(0).method()).isEqualTo("DELETE");
        assertThat(seen.get(0).body()).isEmpty();
        assertThat(seen.get(0).contentType()).isNull();
    }
}
