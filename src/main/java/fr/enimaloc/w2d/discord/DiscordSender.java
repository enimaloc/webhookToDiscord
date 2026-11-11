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
    private static final Duration MAX_429_WAIT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;
    private final Sleeper sleeper;
    private final UnaryOperator<String> urlRewriter;
    private final Duration requestTimeout;

    public DiscordSender() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), d -> Thread.sleep(d));
    }

    public DiscordSender(HttpClient client, Sleeper sleeper) {
        this(client, sleeper, UnaryOperator.identity());
    }

    public DiscordSender(HttpClient client, Sleeper sleeper, UnaryOperator<String> urlRewriter) {
        this(client, sleeper, urlRewriter, REQUEST_TIMEOUT);
    }

    DiscordSender(HttpClient client, Sleeper sleeper, UnaryOperator<String> urlRewriter, Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
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
        String method = m.method();
        String masked = DiscordUrls.mask(m.url());
        HttpRequest request;
        String rewritten = m.url();
        try {
            rewritten = urlRewriter.apply(m.url());
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(rewritten)).timeout(requestTimeout);
            if (m.message() == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(m.message())));
            }
            request = builder.build();
        } catch (Exception e) {
            return new SendResult(method, masked, 0, 0, describe(e, m.url(), rewritten), null, false);
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
                    JsonNode message = messageObject(r.body());
                    return new SendResult(method, masked, status, attempts, message == null ? response : "", message,
                            true);
                }
                if (status == 429) {
                    wait = clamp(retryAfter(r));
                } else if (status >= 500) {
                    wait = backoff(attempts);
                } else {
                    return new SendResult(method, masked, status, attempts, response, null, false);
                }
            } catch (IOException e) {
                status = 0;
                response = describe(e, m.url(), rewritten);
                wait = backoff(attempts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new SendResult(method, masked, 0, attempts, "interrupted", null, false);
            }
            if (attempts > MAX_RETRIES) {
                return new SendResult(method, masked, status, attempts, response, null, false);
            }
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new SendResult(method, masked, status, attempts, response, null, false);
            }
        }
    }

    /** The message object of a 2xx body (Discord sends one for {@code ?wait=true} and PATCH), else null. */
    private static JsonNode messageObject(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode n = Json.MAPPER.readTree(body);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Exception summary with the webhook URL and token scrubbed. */
    private static String describe(Exception e, String url, String rewritten) {
        String msg = String.valueOf(e.getMessage());
        String masked = DiscordUrls.mask(url);
        for (String u : new String[] {url, rewritten}) {
            if (u != null && !u.isEmpty()) {
                msg = msg.replace(u, masked);
            }
        }
        String token = token(url);
        if (token != null && !token.isEmpty()) {
            msg = msg.replace(token, "***");
        }
        return truncate(e.getClass().getSimpleName() + ": " + msg);
    }

    private static String token(String url) {
        if (url == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/webhooks/\\d+/([^/?#]+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static Duration clamp(Duration d) {
        if (d.isNegative()) {
            return Duration.ZERO;
        }
        return d.compareTo(MAX_429_WAIT) > 0 ? MAX_429_WAIT : d;
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
