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
        assertThat(s.sendAll(List.of(msg(), msg()))).hasSize(2);
    }
}
