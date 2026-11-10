package fr.enimaloc.w2d.processing;

import com.fasterxml.jackson.databind.JsonNode;
import fr.enimaloc.w2d.discord.DiscordSender;
import fr.enimaloc.w2d.json.Json;
import io.javalin.Javalin;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Local stand-in for Discord: records the {@code content} of every message posted to webhook 1. */
final class FakeDiscord implements AutoCloseable {
    static final String TOKEN = "secretTok";
    static final String URL = "https://discord.com/api/webhooks/1/" + TOKEN;

    final List<String> contents = new CopyOnWriteArrayList<>();
    private final Javalin server;

    FakeDiscord(int status, long delayMs) {
        server = Javalin.create(cfg -> cfg.routes.post("/api/webhooks/1/" + TOKEN, ctx -> {
            JsonNode body = Json.MAPPER.readTree(ctx.body());
            contents.add(body.path("content").asText());
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            ctx.status(status);
            if (status >= 400) {
                ctx.result("{\"message\":\"bad\"}");
            }
        }));
        server.start(0);
    }

    DiscordSender sender() {
        String base = "http://localhost:" + server.port();
        return new DiscordSender(HttpClient.newHttpClient(), d -> {
        }, u -> u.replace("https://discord.com", base));
    }

    @Override
    public void close() {
        server.stop();
    }
}
