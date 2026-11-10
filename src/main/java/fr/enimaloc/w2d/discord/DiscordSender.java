package fr.enimaloc.w2d.discord;

import com.fasterxml.jackson.databind.JsonNode;
import fr.enimaloc.w2d.json.Json;
import fr.enimaloc.w2d.script.OutgoingMessage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

public class DiscordSender {
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private static final int MAX_RETRIES = 3;
    private static final int MAX_RESPONSE = 2048;
    private static final Duration DEFAULT_429_WAIT = Duration.ofSeconds(1);

    private final HttpClient client;
    private final Sleeper sleeper;
    private final UnaryOperator<String> urlRewriter;

    public DiscordSender() {
        this(HttpClient.newHttpClient(), d -> Thread.sleep(d));
    }

    public DiscordSender(HttpClient client, Sleeper sleeper) {
        this(client, sleeper, UnaryOperator.identity());
    }

    public DiscordSender(HttpClient client, Sleeper sleeper, UnaryOperator<String> urlRewriter) {
        this.client = client;
        this.sleeper = sleeper;
        this.urlRewriter = urlRewriter;
    }

    public List<SendResult> sendAll(List<OutgoingMessage> messages) {
        List<SendResult> results = new ArrayList<>();
        for (OutgoingMessage m : messages) {
            results.add(send(m));
        }
        return results;
    }

    public SendResult send(OutgoingMessage m) {
        String masked = DiscordUrls.mask(m.url());
        String body;
        HttpRequest request;
        try {
            body = Json.MAPPER.writeValueAsString(m.message());
            request = HttpRequest.newBuilder(URI.create(urlRewriter.apply(m.url())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (Exception e) {
            return new SendResult(masked, 0, 0, truncate(String.valueOf(e.getMessage())), false);
        }

        int attempts = 0;
        int status = 0;
        String response = "";
        while (true) {
            attempts++;
            Duration wait;
            try {
                HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString());
                status = r.statusCode();
                response = truncate(r.body());
                if (status >= 200 && status < 300) {
                    return new SendResult(masked, status, attempts, response, true);
                }
                if (status == 429) {
                    wait = retryAfter(r);
                } else if (status >= 500) {
                    wait = backoff(attempts);
                } else {
                    return new SendResult(masked, status, attempts, response, false);
                }
            } catch (IOException e) {
                status = 0;
                response = truncate(e.getClass().getSimpleName() + ": " + e.getMessage());
                wait = backoff(attempts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new SendResult(masked, 0, attempts, "interrupted", false);
            }
            if (attempts > MAX_RETRIES) {
                return new SendResult(masked, status, attempts, response, false);
            }
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new SendResult(masked, status, attempts, response, false);
            }
        }
    }

    private static Duration backoff(int attempt) {
        return Duration.ofSeconds(1L << (attempt - 1));
    }

    private static Duration retryAfter(HttpResponse<String> r) {
        try {
            JsonNode n = Json.MAPPER.readTree(r.body());
            if (n != null && n.has("retry_after") && n.get("retry_after").isNumber()) {
                return Duration.ofMillis(Math.round(n.get("retry_after").asDouble() * 1000));
            }
        } catch (Exception ignored) {
            // fall through to the header
        }
        return r.headers().firstValue("Retry-After").map(v -> {
            try {
                return Duration.ofMillis(Math.round(Double.parseDouble(v.trim()) * 1000));
            } catch (NumberFormatException e) {
                return DEFAULT_429_WAIT;
            }
        }).orElse(DEFAULT_429_WAIT);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_RESPONSE ? s : s.substring(0, MAX_RESPONSE);
    }
}
