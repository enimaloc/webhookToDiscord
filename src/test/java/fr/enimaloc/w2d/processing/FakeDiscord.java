package fr.enimaloc.w2d.processing;

import com.fasterxml.jackson.databind.JsonNode;
import fr.enimaloc.w2d.discord.DiscordSender;
import fr.enimaloc.w2d.json.Json;
import io.javalin.Javalin;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Local stand-in for Discord: records the {@code content} of every message posted to webhook 1. */
public final class FakeDiscord implements AutoCloseable {
    public static final String TOKEN = "secretTok";
    public static final String URL = "https://discord.com/api/webhooks/1/" + TOKEN;

    public final List<String> contents = new CopyOnWriteArrayList<>();
    public final CountDownLatch firstRequest = new CountDownLatch(1);
    public final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Javalin server;

    public FakeDiscord(int status, long delayMs) {
        server = Javalin.create(cfg -> cfg.routes.post("/api/webhooks/1/" + TOKEN, ctx -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                JsonNode body = Json.MAPPER.readTree(ctx.body());
                contents.add(body.path("content").asText());
                firstRequest.countDown();
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            } finally {
                inFlight.decrementAndGet();
            }
            ctx.status(status);
            if (status >= 400) {
                ctx.result("{\"message\":\"bad\"}");
            }
        }));
        server.start(0);
    }

    public DiscordSender sender() {
        String base = "http://localhost:" + server.port();
        return new DiscordSender(HttpClient.newHttpClient(), d -> {
        }, u -> u.replace("https://discord.com", base));
    }

    @Override
    public void close() {
        server.stop();
    }
}
