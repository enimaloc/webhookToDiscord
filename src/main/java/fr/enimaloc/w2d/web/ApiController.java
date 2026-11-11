package fr.enimaloc.w2d.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.enimaloc.w2d.discord.DiscordUrls;
import fr.enimaloc.w2d.endpoint.AuthConfig;
import fr.enimaloc.w2d.endpoint.Endpoint;
import fr.enimaloc.w2d.endpoint.EndpointRepository;
import fr.enimaloc.w2d.history.Execution;
import fr.enimaloc.w2d.history.HistoryRepository;
import fr.enimaloc.w2d.history.IncomingRequest;
import fr.enimaloc.w2d.history.IncomingRequest.RequestStatus;
import fr.enimaloc.w2d.json.Json;
import fr.enimaloc.w2d.processing.Pipeline;
import fr.enimaloc.w2d.processing.PipelineOutcome;
import fr.enimaloc.w2d.script.OutgoingMessage;
import fr.enimaloc.w2d.script.ScriptRequest;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/** JSON API of the admin panel. Must be registered after {@link AdminAuth}, which guards {@code /api/*}. */
public final class ApiController {
    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_NAME_LENGTH = 200;
    /** Lower-cased alias → Jackson type id of {@link AuthConfig}. */
    private static final Map<String, String> AUTH_TYPES = Map.of(
            "none", "none", "secretheader", "secretHeader", "bearer", "bearer", "basic", "basic", "hmac", "hmac");

    private final EndpointRepository endpoints;
    private final HistoryRepository history;
    private final Pipeline pipeline;
    private final String publicBaseUrl;

    public ApiController(EndpointRepository endpoints, HistoryRepository history, Pipeline pipeline,
                         String publicBaseUrl) {
        this.endpoints = endpoints;
        this.history = history;
        this.pipeline = pipeline;
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    /** Client error carried to a JSON {@code {"error": ...}} response. */
    static final class ApiException extends RuntimeException {
        final HttpStatus status;

        ApiException(HttpStatus status, String message) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    public void register(RoutesConfig routes) {
        routes.exception(ApiException.class, (e, ctx) ->
                json(ctx, e.status, Json.MAPPER.createObjectNode().put("error", e.getMessage())));
        routes.get("/api/endpoints", this::listEndpoints);
        routes.post("/api/endpoints", this::createEndpoint);
        routes.get("/api/endpoints/{id}", ctx -> json(ctx, HttpStatus.OK, detail(endpoint(ctx))));
        routes.put("/api/endpoints/{id}", this::updateEndpoint);
        routes.delete("/api/endpoints/{id}", this::deleteEndpoint);
        routes.get("/api/endpoints/{id}/requests", this::listRequests);
        routes.get("/api/requests/{rid}", this::requestDetail);
        routes.post("/api/requests/{rid}/replay", this::replay);
    }

    private void listEndpoints(Context ctx) {
        ArrayNode out = Json.MAPPER.createArrayNode();
        for (Endpoint e : endpoints.list()) {
            ObjectNode node = out.addObject()
                    .put("id", e.id())
                    .put("name", e.name())
                    .put("enabled", e.enabled())
                    .put("url", url(e));
            node.put("lastReceived", history.lastReceived(e.id()).map(Instant::toString).orElse(null));
        }
        json(ctx, HttpStatus.OK, out);
    }

    private void createEndpoint(Context ctx) {
        JsonNode body = body(ctx);
        Endpoint created = endpoints.create(name(body));
        json(ctx, HttpStatus.CREATED, detail(created));
    }

    private void updateEndpoint(Context ctx) {
        Endpoint existing = endpoint(ctx);
        JsonNode body = body(ctx);
        String name = name(body);
        JsonNode enabled = body.get("enabled");
        if (enabled == null || !enabled.isBoolean()) {
            throw badRequest("enabled must be a boolean");
        }
        JsonNode script = body.get("script");
        if (script == null || !script.isTextual()) {
            throw badRequest("script must be a string");
        }
        rejectNul(script.asText(), "script");
        AuthConfig auth = existing.auth();
        JsonNode authNode = body.get("auth");
        if (authNode != null && !authNode.isNull()) {
            auth = parseAuth(authNode).mergeSecretsFrom(existing.auth());
            requireComplete(auth);
        }
        Endpoint updated;
        try {
            updated = endpoints.update(new Endpoint(existing.id(), name, enabled.booleanValue(), script.asText(), auth,
                    existing.createdAt(), existing.updatedAt()));
        } catch (IllegalArgumentException e) {
            // deleted concurrently
            throw notFound();
        }
        json(ctx, HttpStatus.OK, detail(updated));
    }

    private void deleteEndpoint(Context ctx) {
        if (!endpoints.delete(ctx.pathParam("id"))) {
            throw notFound();
        }
        ctx.status(HttpStatus.NO_CONTENT);
    }

    private void listRequests(Context ctx) {
        Endpoint e = endpoint(ctx);
        int page = intParam(ctx, "page", 0);
        int size = intParam(ctx, "size", DEFAULT_PAGE_SIZE);
        if (page < 0) {
            throw badRequest("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw badRequest("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        ArrayNode out = Json.MAPPER.createArrayNode();
        for (IncomingRequest r : history.page(e.id(), page, size)) {
            out.addObject()
                    .put("id", r.id())
                    .put("receivedAt", r.receivedAt().toString())
                    .put("method", r.method())
                    .put("status", lower(r.status()));
        }
        json(ctx, HttpStatus.OK, out);
    }

    private void requestDetail(Context ctx) {
        IncomingRequest r = request(ctx);
        ObjectNode out = Json.MAPPER.createObjectNode()
                .put("id", r.id())
                .put("endpointId", r.endpointId())
                .put("receivedAt", r.receivedAt().toString())
                .put("method", r.method());
        out.set("headers", Json.MAPPER.valueToTree(r.headers()));
        out.put("query", r.query());
        out.put("body", ScriptRequest.from(r).body());
        out.put("status", lower(r.status()));
        ArrayNode executions = out.putArray("executions");
        for (Execution x : history.executionsOf(r.id())) {
            ObjectNode node = executions.addObject()
                    .put("id", x.id())
                    .put("startedAt", x.startedAt().toString())
                    .put("mode", lower(x.mode()))
                    .put("status", lower(x.status()))
                    .put("logs", x.logs());
            node.set("messages", x.messages());
            node.set("sends", x.sendResults());
            node.put("error", x.error());
        }
        json(ctx, HttpStatus.OK, out);
    }

    private void replay(Context ctx) {
        IncomingRequest r = request(ctx);
        JsonNode body = body(ctx);
        JsonNode modeNode = body.get("mode");
        String modeText = modeNode != null && modeNode.isTextual() ? modeNode.asText() : "";
        Execution.Mode mode = switch (modeText) {
            case "preview" -> Execution.Mode.PREVIEW;
            case "send" -> Execution.Mode.REPLAY;
            default -> throw badRequest("mode must be \"preview\" or \"send\"");
        };
        JsonNode scriptNode = body.get("script");
        if (scriptNode != null && !scriptNode.isNull() && !scriptNode.isTextual()) {
            throw badRequest("script must be a string");
        }
        Endpoint e = endpoints.find(r.endpointId()).orElseThrow(ApiController::notFound);
        // an inline script is an unsaved editor preview: it is never written back to the entry
        String script = scriptNode != null && scriptNode.isTextual() ? scriptNode.asText() : e.script();

        // a pending request is owned by the drain: take it over atomically or it would be delivered twice
        boolean claimed = false;
        if (mode == Execution.Mode.REPLAY && r.status() == RequestStatus.PENDING) {
            if (!history.claimPending(r.id())) {
                throw new ApiException(HttpStatus.CONFLICT, "request is being processed");
            }
            claimed = true;
        }
        PipelineOutcome outcome;
        try {
            outcome = pipeline.execute(r, script, mode);
        } catch (RuntimeException ex) {
            if (claimed) {
                history.setStatus(r.id(), RequestStatus.FAILED);
            }
            throw ex;
        }
        boolean success = outcome.status() == Execution.Status.SUCCESS;
        if (claimed) {
            history.setStatus(r.id(), success ? RequestStatus.DONE : RequestStatus.FAILED);
        } else if (mode == Execution.Mode.REPLAY && success
                && (r.status() == RequestStatus.FAILED || r.status() == RequestStatus.PROCESSING)) {
            history.setStatus(r.id(), RequestStatus.DONE);
        }

        ObjectNode out = Json.MAPPER.createObjectNode()
                .put("executionId", outcome.executionId())
                .put("status", lower(outcome.status()))
                .put("logs", outcome.script().logs())
                .put("error", error(outcome));
        ArrayNode messages = out.putArray("messages");
        for (OutgoingMessage m : outcome.script().messages()) {
            messages.addObject().put("method", m.method()).put("url", DiscordUrls.mask(m.url()))
                    .set("message", m.message());
        }
        out.set("sends", Json.MAPPER.valueToTree(outcome.sends()));
        json(ctx, HttpStatus.OK, out);
    }

    private static String error(PipelineOutcome outcome) {
        if (!outcome.script().ok()) {
            return outcome.script().error();
        }
        long failed = outcome.sends().stream().filter(s -> !s.ok()).count();
        return failed > 0 ? failed + " of " + outcome.sends().size() + " message(s) failed to send" : null;
    }

    private ObjectNode detail(Endpoint e) {
        ObjectNode node = Json.MAPPER.createObjectNode()
                .put("id", e.id())
                .put("name", e.name())
                .put("enabled", e.enabled())
                .put("url", url(e))
                .put("script", e.script());
        node.set("auth", Json.MAPPER.valueToTree(e.auth().redacted()));
        return node;
    }

    private String url(Endpoint e) {
        return publicBaseUrl + "/webhook/" + e.id();
    }

    private Endpoint endpoint(Context ctx) {
        return endpoints.find(ctx.pathParam("id")).orElseThrow(ApiController::notFound);
    }

    private IncomingRequest request(Context ctx) {
        long id;
        try {
            id = Long.parseLong(ctx.pathParam("rid"));
        } catch (NumberFormatException e) {
            throw notFound();
        }
        return history.findRequest(id).orElseThrow(ApiController::notFound);
    }

    private static JsonNode body(Context ctx) {
        JsonNode node;
        try {
            node = Json.MAPPER.readTree(ctx.body());
        } catch (JsonProcessingException e) {
            throw badRequest("invalid JSON body");
        }
        if (node == null || !node.isObject()) {
            throw badRequest("body must be a JSON object");
        }
        return node;
    }

    private static String name(JsonNode body) {
        JsonNode name = body.get("name");
        if (name == null || !name.isTextual() || name.asText().isBlank()) {
            throw badRequest("name is required");
        }
        String value = name.asText().strip();
        rejectNul(value, "name");
        if (value.length() > MAX_NAME_LENGTH) {
            throw badRequest("name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return value;
    }

    private static AuthConfig parseAuth(JsonNode node) {
        if (!node.isObject() || !node.path("type").isTextual()) {
            throw badRequest("auth must be an object with a type");
        }
        String type = AUTH_TYPES.get(node.path("type").asText().toLowerCase(Locale.ROOT));
        if (type == null) {
            throw badRequest("unknown auth type");
        }
        ObjectNode normalized = ((ObjectNode) node).deepCopy().put("type", type);
        normalized.properties().forEach(field -> {
            if (field.getValue().isTextual()) {
                rejectNul(field.getValue().asText(), "auth." + field.getKey());
            }
        });
        try {
            AuthConfig auth = Json.MAPPER.treeToValue(normalized, AuthConfig.class);
            if (auth == null) {
                throw badRequest("invalid auth");
            }
            return auth;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw badRequest("invalid auth");
        }
    }

    /** PostgreSQL text and jsonb cannot store U+0000. */
    private static void rejectNul(String value, String field) {
        if (value.indexOf('\u0000') >= 0) {
            throw badRequest(field + " must not contain NUL characters");
        }
    }

    /** After merging stored secrets, an auth config missing its secret would reject every webhook call. */
    private static void requireComplete(AuthConfig auth) {
        String missing = switch (auth) {
            case AuthConfig.None n -> null;
            case AuthConfig.Bearer b -> blank(b.token()) ? "auth.token is required" : null;
            case AuthConfig.SecretHeader h -> blank(h.header()) ? "auth.header is required"
                    : blank(h.value()) ? "auth.value is required" : null;
            case AuthConfig.Basic b -> b.username() == null ? "auth.username is required"
                    : b.password() == null || b.password().isEmpty() ? "auth.password is required" : null;
            case AuthConfig.Hmac h -> blank(h.header()) ? "auth.header is required"
                    : blank(h.secret()) ? "auth.secret is required"
                    : !macAvailable(h.algorithm()) ? "auth.algorithm is not supported" : null;
        };
        if (missing != null) {
            throw badRequest(missing);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean macAvailable(String algorithm) {
        try {
            javax.crypto.Mac.getInstance(algorithm);
            return true;
        } catch (java.security.NoSuchAlgorithmException e) {
            return false;
        }
    }

    private static int intParam(Context ctx, String name, int fallback) {
        String value = ctx.queryParam(name);
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw badRequest(name + " must be an integer");
        }
    }

    private static void json(Context ctx, HttpStatus status, JsonNode node) {
        try {
            ctx.status(status).contentType("application/json").result(Json.MAPPER.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not found");
    }
}
