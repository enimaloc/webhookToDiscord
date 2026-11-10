package fr.enimaloc.w2d.web;

import fr.enimaloc.w2d.endpoint.AuthConfig;
import fr.enimaloc.w2d.endpoint.Endpoint;
import fr.enimaloc.w2d.endpoint.EndpointRepository;
import fr.enimaloc.w2d.history.HistoryRepository;
import fr.enimaloc.w2d.history.IncomingRequest;
import fr.enimaloc.w2d.support.PostgresTest;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookControllerTest extends PostgresTest {
    private EndpointRepository endpoints;
    private HistoryRepository history;
    private final List<String> triggered = new ArrayList<>();

    @BeforeEach
    void setUp() {
        endpoints = new EndpointRepository(jdbi());
        history = new HistoryRepository(jdbi());
        triggered.clear();
    }

    private Javalin app(long maxBody) {
        var controller = new WebhookController(endpoints, history, triggered::add, maxBody);
        return Javalin.create(cfg -> {
            cfg.http.maxRequestSize = maxBody;
            controller.register(cfg.routes);
        });
    }

    private Endpoint create(AuthConfig auth, boolean enabled) {
        Endpoint e = endpoints.create("e");
        return endpoints.update(new Endpoint(e.id(), e.name(), enabled, e.script(), auth, e.createdAt(), e.updatedAt()));
    }

    private static HttpResponse<String> post(Javalin server, String path, HttpRequest.BodyPublisher body,
                                             String... headers) {
        try {
            var b = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                    .header("Content-Type", "text/plain").POST(body);
            if (headers.length > 0) b.header(headers[0], headers[1]);
            return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpResponse<String> post(Javalin server, String path, String body, String... headers) {
        return post(server, path, HttpRequest.BodyPublishers.ofString(body), headers);
    }

    private List<IncomingRequest> stored(String id) {
        return history.page(id, 0, 50);
    }

    @Test
    void unknownEndpoint404() {
        JavalinTest.test(app(1024), (server, client) -> {
            assertThat(post(server, "/webhook/nope", "x").statusCode()).isEqualTo(404);
            assertThat(triggered).isEmpty();
        });
    }

    @Test
    void disabledEndpoint404() {
        Endpoint e = create(new AuthConfig.None(), false);
        JavalinTest.test(app(1024), (server, client) -> {
            assertThat(post(server, "/webhook/" + e.id(), "x").statusCode()).isEqualTo(404);
            assertThat(stored(e.id())).isEmpty();
            assertThat(triggered).isEmpty();
        });
    }

    @Test
    void acceptsAndStores() {
        Endpoint e = create(new AuthConfig.None(), true);
        JavalinTest.test(app(1024), (server, client) -> {
            var r = post(server, "/webhook/" + e.id(), "hello");
            assertThat(r.statusCode()).isEqualTo(202);
            List<IncomingRequest> rows = stored(e.id());
            assertThat(rows).hasSize(1);
            assertThat(new String(history.findRequest(rows.get(0).id()).orElseThrow().body(), StandardCharsets.UTF_8))
                    .isEqualTo("hello");
            assertThat(rows.get(0).status()).isEqualTo(IncomingRequest.RequestStatus.PENDING);
            assertThat(r.body()).contains("\"requestId\":" + rows.get(0).id());
            assertThat(triggered).containsExactly(e.id());
        });
    }

    @Test
    void authRequired401() {
        Endpoint e = create(new AuthConfig.Bearer("t"), true);
        JavalinTest.test(app(1024), (server, client) -> {
            assertThat(post(server, "/webhook/" + e.id(), "x").statusCode()).isEqualTo(401);
            assertThat(stored(e.id())).isEmpty();
            assertThat(triggered).isEmpty();

            var r = post(server, "/webhook/" + e.id(), "x", "Authorization", "Bearer t");
            assertThat(r.statusCode()).isEqualTo(202);
            assertThat(stored(e.id()).get(0).headers().get("authorization")).containsExactly("***");
        });
    }

    @Test
    void bodyTooLarge413() {
        Endpoint e = create(new AuthConfig.None(), true);
        JavalinTest.test(app(10), (server, client) -> {
            assertThat(post(server, "/webhook/" + e.id(), "12345678901").statusCode()).isEqualTo(413);
            assertThat(stored(e.id())).isEmpty();
            assertThat(triggered).isEmpty();
        });
    }

    @Test
    void chunkedBodyTooLarge413() {
        Endpoint e = create(new AuthConfig.None(), true);
        JavalinTest.test(app(10), (server, client) -> {
            // unknown length => chunked transfer encoding
            var chunked = HttpRequest.BodyPublishers.ofInputStream(
                    () -> new ByteArrayInputStream("12345678901".getBytes(StandardCharsets.UTF_8)));
            assertThat(post(server, "/webhook/" + e.id(), chunked).statusCode()).isEqualTo(413);
            assertThat(stored(e.id())).isEmpty();
            assertThat(triggered).isEmpty();
        });
    }

    @Test
    void schedulerFailureStill202() {
        Endpoint e = create(new AuthConfig.None(), true);
        var controller = new WebhookController(endpoints, history, id -> {
            throw new java.util.concurrent.RejectedExecutionException("full");
        }, 1024);
        var app = Javalin.create(cfg -> controller.register(cfg.routes));
        JavalinTest.test(app, (server, client) -> {
            assertThat(post(server, "/webhook/" + e.id(), "x").statusCode()).isEqualTo(202);
            assertThat(stored(e.id())).hasSize(1);
        });
    }

    @Test
    void queryStored() {
        Endpoint e = create(new AuthConfig.None(), true);
        JavalinTest.test(app(1024), (server, client) -> {
            assertThat(post(server, "/webhook/" + e.id() + "?a=1&b=2", "x").statusCode()).isEqualTo(202);
            assertThat(stored(e.id()).get(0).query()).isEqualTo("a=1&b=2");
        });
    }

    @Test
    void formUrlEncodedBodyStoredRaw() {
        Endpoint e = create(new AuthConfig.None(), true);
        JavalinTest.test(app(1024), (server, client) -> {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/webhook/" + e.id()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("a=1&b=2")).build();
            var r = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(202);
            List<IncomingRequest> rows = stored(e.id());
            assertThat(rows).hasSize(1);
            assertThat(new String(history.findRequest(rows.get(0).id()).orElseThrow().body(), StandardCharsets.UTF_8))
                    .isEqualTo("a=1&b=2");
            assertThat(rows.get(0).query()).isEmpty();
        });
    }
}
