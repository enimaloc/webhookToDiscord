package fr.enimaloc.w2d.web;

import fr.enimaloc.w2d.endpoint.AuthVerifier;
import fr.enimaloc.w2d.endpoint.Endpoint;
import fr.enimaloc.w2d.endpoint.EndpointRepository;
import fr.enimaloc.w2d.history.HistoryRepository;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Public ingestion endpoint: stores the raw request, then asks the processor to drain the entry. */
public final class WebhookController {
    private final EndpointRepository endpoints;
    private final HistoryRepository history;
    private final Consumer<String> onAccepted;
    private final long maxBodyBytes;

    public WebhookController(EndpointRepository endpoints, HistoryRepository history, Consumer<String> onAccepted,
                             long maxBodyBytes) {
        this.endpoints = endpoints;
        this.history = history;
        this.onAccepted = onAccepted;
        this.maxBodyBytes = maxBodyBytes;
    }

    public void register(RoutesConfig routes) {
        Handler h = this::handle;
        routes.get("/webhook/{id}", h);
        routes.post("/webhook/{id}", h);
        routes.put("/webhook/{id}", h);
        routes.patch("/webhook/{id}", h);
        routes.delete("/webhook/{id}", h);
    }

    private void handle(Context ctx) throws IOException {
        Endpoint endpoint = endpoints.find(ctx.pathParam("id")).filter(Endpoint::enabled).orElse(null);
        if (endpoint == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            return;
        }
        if (ctx.contentLength() > maxBodyBytes) {
            ctx.status(HttpStatus.CONTENT_TOO_LARGE);
            return;
        }
        byte[] body = readBounded(ctx.req());
        if (body == null) {
            ctx.status(HttpStatus.CONTENT_TOO_LARGE);
            return;
        }
        if (!AuthVerifier.verify(endpoint.auth(), ctx::header, body)) {
            ctx.status(HttpStatus.UNAUTHORIZED);
            return;
        }
        long id = history.insertPending(endpoint.id(), ctx.method().name(), headers(ctx.req()),
                ctx.queryString() == null ? "" : ctx.queryString(),
                body, AuthVerifier.maskedHeaders(endpoint.auth()));
        onAccepted.accept(endpoint.id());
        ctx.status(HttpStatus.ACCEPTED).json(Map.of("requestId", id));
    }

    /** Reads at most maxBodyBytes; returns null when the body is larger. */
    private byte[] readBounded(HttpServletRequest req) throws IOException {
        try (InputStream in = req.getInputStream()) {
            byte[] data = in.readNBytes((int) Math.min(maxBodyBytes + 1, Integer.MAX_VALUE - 8));
            return data.length > maxBodyBytes ? null : data;
        }
    }

    private static Map<String, List<String>> headers(HttpServletRequest req) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String name : Collections.list(req.getHeaderNames())) {
            out.put(name, new ArrayList<>(Collections.list(req.getHeaders(name))));
        }
        return out;
    }
}
