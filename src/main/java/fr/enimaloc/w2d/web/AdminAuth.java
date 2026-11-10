package fr.enimaloc.w2d.web;

import com.fasterxml.jackson.databind.JsonNode;
import fr.enimaloc.w2d.json.Json;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.HttpStatus;
import io.javalin.http.SameSite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Password login issuing a signed session cookie, plus the guard protecting {@code /api/*}. */
public final class AdminAuth {
    public static final String COOKIE = "w2d_session";
    static final Duration SESSION_TTL = Duration.ofHours(12);
    private static final long MAX_DELAY_SECONDS = 60;
    /** Failure counters are forgotten once the client has been quiet this long after its delay expired. */
    private static final Duration FORGET_AFTER = Duration.ofMinutes(15);
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final byte[] passwordDigest;
    private final SessionTokens tokens;
    private final Clock clock;
    private final Object lock = new Object();
    private final Map<String, Failures> failures = new HashMap<>();

    private record Failures(int count, Instant blockedUntil) {
    }

    public AdminAuth(String adminPassword, SessionTokens tokens, Clock clock) {
        if (adminPassword == null || adminPassword.isEmpty()) {
            throw new IllegalArgumentException("Admin password must not be empty");
        }
        this.passwordDigest = sha256(adminPassword);
        this.tokens = tokens;
        this.clock = clock;
    }

    public void register(RoutesConfig routes) {
        routes.beforeMatched("/api/*", this::guard);
        routes.post("/api/login", this::login);
        routes.post("/api/logout", this::logout);
        routes.get("/api/session", ctx -> ctx.json(Map.of("authenticated", authenticated(ctx))));
    }

    public boolean authenticated(Context ctx) {
        return tokens.valid(ctx.cookie(COOKIE));
    }

    private void guard(Context ctx) {
        // the matched route, not the raw URI, so dot-segments or encodings cannot dodge the check
        String path = ctx.endpoints().matchedHttpEndpoint().path;
        if (path.equals("/api/login") || path.equals("/api/session")) {
            return;
        }
        if (!authenticated(ctx)) {
            ctx.skipRemainingHandlers();
            ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "unauthorized"));
        }
    }

    private void login(Context ctx) {
        String password = readPassword(ctx.body());
        if (password == null) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "password is required"));
            return;
        }
        String client = clientKey(ctx);
        boolean ok;
        long retryAfter;
        // Serialized so parallel guesses from one client cannot slip in before the failure is recorded.
        synchronized (lock) {
            Instant now = clock.instant();
            Failures previous = failures.get(client);
            if (previous != null && now.isBefore(previous.blockedUntil())) {
                retryAfter = Math.max(1, Duration.between(now, previous.blockedUntil()).toSeconds());
                ctx.header("Retry-After", Long.toString(retryAfter));
                ctx.status(HttpStatus.TOO_MANY_REQUESTS).json(Map.of("error", "too many attempts"));
                return;
            }
            ok = MessageDigest.isEqual(passwordDigest, sha256(password));
            if (ok) {
                failures.remove(client);
            } else {
                int count = previous == null ? 1 : previous.count() + 1;
                long delay = count > 7 ? MAX_DELAY_SECONDS : Math.min(1L << (count - 1), MAX_DELAY_SECONDS);
                prune(now);
                failures.put(client, new Failures(count, now.plusSeconds(delay)));
            }
        }
        if (!ok) {
            ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "invalid password"));
            return;
        }
        ctx.cookie(cookie(ctx, tokens.issue(SESSION_TTL), (int) SESSION_TTL.toSeconds()));
        ctx.status(HttpStatus.NO_CONTENT);
    }

    private void logout(Context ctx) {
        ctx.cookie(cookie(ctx, "", 0));
        ctx.status(HttpStatus.NO_CONTENT);
    }

    /** Must be called with {@link #lock} held. */
    private void prune(Instant now) {
        failures.values().removeIf(f -> now.isAfter(f.blockedUntil().plus(FORGET_AFTER)));
        if (failures.size() >= MAX_TRACKED_CLIENTS) {
            // under a flood of distinct clients, keep only those currently blocked
            failures.values().removeIf(f -> !now.isBefore(f.blockedUntil()));
        }
    }

    int trackedClients() {
        synchronized (lock) {
            return failures.size();
        }
    }

    private static Cookie cookie(Context ctx, String value, int maxAge) {
        return new Cookie(COOKIE, value, "/", maxAge, secure(ctx), true, null, SameSite.STRICT);
    }

    private static boolean secure(Context ctx) {
        String proto = firstValue(ctx.header("X-Forwarded-Proto"));
        return "https".equalsIgnoreCase(proto) || "https".equalsIgnoreCase(ctx.scheme());
    }

    /** The app runs behind Traefik, which sets X-Forwarded-For; its first value is the client. */
    static String clientKey(Context ctx) {
        String forwarded = firstValue(ctx.header("X-Forwarded-For"));
        return forwarded != null && !forwarded.isEmpty() ? forwarded : ctx.ip();
    }

    private static String firstValue(String header) {
        if (header == null) {
            return null;
        }
        int comma = header.indexOf(',');
        return (comma < 0 ? header : header.substring(0, comma)).trim();
    }

    private static String readPassword(String body) {
        try {
            JsonNode node = Json.MAPPER.readTree(body);
            JsonNode password = node == null ? null : node.get("password");
            return password != null && password.isTextual() ? password.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
