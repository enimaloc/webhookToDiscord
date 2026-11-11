package fr.enimaloc.w2d.web;

import com.fasterxml.jackson.databind.JsonNode;
import fr.enimaloc.w2d.endpoint.AuthConfig;
import fr.enimaloc.w2d.endpoint.Endpoint;
import fr.enimaloc.w2d.endpoint.EndpointRepository;
import fr.enimaloc.w2d.history.Execution;
import fr.enimaloc.w2d.history.HistoryRepository;
import fr.enimaloc.w2d.history.IncomingRequest.RequestStatus;
import fr.enimaloc.w2d.json.Json;
import fr.enimaloc.w2d.processing.FakeDiscord;
import fr.enimaloc.w2d.processing.Pipeline;
import fr.enimaloc.w2d.script.ScriptRunner;
import fr.enimaloc.w2d.store.StoreRepository;
import fr.enimaloc.w2d.support.PostgresTest;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiControllerTest extends PostgresTest {
    private static final String BASE_URL = "https://relay.example.com";
    private static final String PASSWORD = "admin-pw";

    private EndpointRepository endpoints;
    private HistoryRepository history;
    private ScriptRunner runner;
    private FakeDiscord discord;

    @BeforeEach
    void setUp() {
        endpoints = new EndpointRepository(jdbi());
        history = new HistoryRepository(jdbi());
        runner = new ScriptRunner(Duration.ofSeconds(5));
        discord = new FakeDiscord(204, 0);
    }

    @AfterEach
    void tearDown() {
        runner.close();
        discord.close();
    }

    private Javalin app() {
        Clock clock = Clock.systemUTC();
        AdminAuth auth = new AdminAuth(PASSWORD, SessionTokens.forAdmin("k".getBytes(StandardCharsets.UTF_8), PASSWORD, clock), clock);
        ApiController api = new ApiController(endpoints, history,
                new Pipeline(endpoints, history, runner, discord.sender(), new StoreRepository(db.jdbi())), BASE_URL);
        return Javalin.create(cfg -> {
            auth.register(cfg.routes);
            api.register(cfg.routes);
        });
    }

    private static HttpResponse<String> send(Javalin server, String method, String path, String body,
                                             String cookie) {
        try {
            var b = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            if (cookie != null) {
                b.header("Cookie", cookie);
            }
            return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String login(Javalin server) {
        var r = send(server, "POST", "/api/login", "{\"password\":\"" + PASSWORD + "\"}", null);
        assertThat(r.statusCode()).isEqualTo(204);
        String setCookie = r.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException(r.body(), e);
        }
    }

    private static String script(String content) {
        return """
                function handle(q) {
                  return [{ url: "%s", message: { content: "%s" } }];
                }
                """.formatted(FakeDiscord.URL, content);
    }

    private long storedRequest(String endpointId) {
        return history.insertPending(endpointId, "POST", Map.of("x-test", java.util.List.of("1")), "a=1",
                "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8), Set.of());
    }

    @Test
    void apiRequiresSession() {
        JavalinTest.test(app(), (server, client) -> {
            assertThat(send(server, "GET", "/api/endpoints", null, null).statusCode()).isEqualTo(401);
            assertThat(send(server, "POST", "/api/endpoints", "{\"name\":\"x\"}", null).statusCode()).isEqualTo(401);
            assertThat(endpoints.list()).isEmpty();
        });
    }

    @Test
    void loginWrongPassword401ThenThrottled() {
        JavalinTest.test(app(), (server, client) -> {
            assertThat(send(server, "POST", "/api/login", "{\"password\":\"wrong\"}", null).statusCode())
                    .isEqualTo(401);
            assertThat(send(server, "POST", "/api/login", "{\"password\":\"" + PASSWORD + "\"}", null).statusCode())
                    .isEqualTo(429);
        });
    }

    @Test
    void crudFlow() {
        JavalinTest.test(app(), (server, client) -> {
            String cookie = login(server);

            var created = send(server, "POST", "/api/endpoints", "{\"name\":\"GitHub\"}", cookie);
            assertThat(created.statusCode()).isEqualTo(201);
            JsonNode entry = json(created);
            String id = entry.get("id").asText();
            assertThat(entry.get("name").asText()).isEqualTo("GitHub");
            assertThat(entry.get("enabled").asBoolean()).isTrue();
            assertThat(entry.get("url").asText()).isEqualTo(BASE_URL + "/webhook/" + id);
            assertThat(entry.get("script").asText()).contains("function handle");
            assertThat(entry.get("auth").get("type").asText()).isEqualTo("none");

            JsonNode list = json(send(server, "GET", "/api/endpoints", null, cookie));
            assertThat(list).hasSize(1);
            assertThat(list.get(0).get("id").asText()).isEqualTo(id);
            assertThat(list.get(0).get("url").asText()).isEqualTo(BASE_URL + "/webhook/" + id);
            assertThat(list.get(0).get("lastReceived").isNull()).isTrue();

            String newScript = script("updated");
            String body = Json.MAPPER.createObjectNode().put("name", "GitHub 2").put("enabled", false)
                    .put("script", newScript).toString();
            var put = send(server, "PUT", "/api/endpoints/" + id, body, cookie);
            assertThat(put.statusCode()).isEqualTo(200);
            assertThat(json(put).get("script").asText()).isEqualTo(newScript);

            JsonNode got = json(send(server, "GET", "/api/endpoints/" + id, null, cookie));
            assertThat(got.get("name").asText()).isEqualTo("GitHub 2");
            assertThat(got.get("enabled").asBoolean()).isFalse();
            assertThat(got.get("script").asText()).isEqualTo(newScript);

            storedRequest(id);
            JsonNode listAfter = json(send(server, "GET", "/api/endpoints", null, cookie));
            assertThat(listAfter.get(0).get("lastReceived").isTextual()).isTrue();

            assertThat(send(server, "DELETE", "/api/endpoints/" + id, null, cookie).statusCode()).isEqualTo(204);
            assertThat(send(server, "GET", "/api/endpoints/" + id, null, cookie).statusCode()).isEqualTo(404);
            assertThat(send(server, "DELETE", "/api/endpoints/" + id, null, cookie).statusCode()).isEqualTo(404);
            assertThat(send(server, "PUT", "/api/endpoints/" + id, body, cookie).statusCode()).isEqualTo(404);
        });
    }

    @Test
    void secretsAreNeverReturned() {
        Endpoint e = endpoints.create("e");
        JavalinTest.test(app(), (server, client) -> {
            String cookie = login(server);
            String body = """
                    {"name":"e","enabled":true,"script":"function handle(q){return [];}",
                     "auth":{"type":"Bearer","token":"s3cr3t"}}""";
            var put = send(server, "PUT", "/api/endpoints/" + e.id(), body, cookie);
            assertThat(put.statusCode()).isEqualTo(200);
            assertThat(put.body()).doesNotContain("s3cr3t");
            assertThat(json(put).get("auth").get("type").asText()).isEqualTo("bearer");
            var get = send(server, "GET", "/api/endpoints/" + e.id(), null, cookie);
            assertThat(get.body()).doesNotContain("s3cr3t");
            assertThat(endpoints.find(e.id()).orElseThrow().auth()).isEqualTo(new AuthConfig.Bearer("s3cr3t"));

            String keep = """
                    {"name":"e","enabled":true,"script":"function handle(q){return [];}",
                     "auth":{"type":"bearer","token":null}}""";
            var put2 = send(server, "PUT", "/api/endpoints/" + e.id(), keep, cookie);
            assertThat(put2.statusCode()).isEqualTo(200);
            assertThat(put2.body()).doesNotContain("s3cr3t");
            assertThat(endpoints.find(e.id()).orElseThrow().auth()).isEqualTo(new AuthConfig.Bearer("s3cr3t"));

            assertThat(send(server, "GET", "/api/endpoints", null, cookie).body()).doesNotContain("s3cr3t");
        });
    }

    @Test
    void requestHistory() {
        Endpoint e = endpoints.create("e");
        long rid = storedRequest(e.id());
        JavalinTest.test(app(), (server, client) -> {
            String cookie = login(server);
            JsonNode page = json(send(server, "GET", "/api/endpoints/" + e.id() + "/requests?page=0&size=10",
                    null, cookie));
            assertThat(page).hasSize(1);
            assertThat(page.get(0).get("id").asLong()).isEqualTo(rid);
            assertThat(page.get(0).get("method").asText()).isEqualTo("POST");
            assertThat(page.get(0).get("status").asText()).isEqualTo("pending");
            assertThat(page.get(0).get("receivedAt").isTextual()).isTrue();

            JsonNode detail = json(send(server, "GET", "/api/requests/" + rid, null, cookie));
            assertThat(detail.get("endpointId").asText()).isEqualTo(e.id());
            assertThat(detail.get("body").asText()).isEqualTo("{\"hello\":\"world\"}");
            assertThat(detail.get("query").asText()).isEqualTo("a=1");
            assertThat(detail.get("headers").get("x-test").get(0).asText()).isEqualTo("1");
            assertThat(detail.get("executions")).isEmpty();

            assertThat(send(server, "GET", "/api/endpoints/nope/requests", null, cookie).statusCode())
                    .isEqualTo(404);
            assertThat(send(server, "GET", "/api/requests/999999", null, cookie).statusCode()).isEqualTo(404);
            assertThat(send(server, "GET", "/api/requests/abc", null, cookie).statusCode()).isEqualTo(404);
            assertThat(send(server, "POST", "/api/requests/999999/replay", "{\"mode\":\"preview\"}", cookie)
                    .statusCode()).isEqualTo(404);
        });
    }

    @Test
    void invalidBodiesAre400() {
        Endpoint e = endpoints.create("e");
        long rid = storedRequest(e.id());
        JavalinTest.test(app(), (server, client) -> {
            String cookie = login(server);
            String path = "/api/endpoints/" + e.id();
            assertThat(send(server, "POST", "/api/endpoints", "not json", cookie).statusCode()).isEqualTo(400);
            assertThat(send(server, "POST", "/api/endpoints", "{}", cookie).statusCode()).isEqualTo(400);
            assertThat(send(server, "POST", "/api/endpoints", "{\"name\":\"  \"}", cookie).statusCode())
                    .isEqualTo(400);
            assertThat(send(server, "POST", "/api/endpoints", "[1]", cookie).statusCode()).isEqualTo(400);
            var bad = send(server, "PUT", path, "{\"name\":\"e\",\"enabled\":\"yes\",\"script\":\"\"}", cookie);
            assertThat(bad.statusCode()).isEqualTo(400);
            assertThat(json(bad).get("error").isTextual()).isTrue();
            assertThat(send(server, "PUT", path, "{\"name\":\"e\",\"enabled\":true}", cookie).statusCode())
                    .isEqualTo(400);
            assertThat(send(server, "PUT", path,
                    "{\"name\":\"e\",\"enabled\":true,\"script\":\"\",\"auth\":{\"type\":\"magic\"}}", cookie)
                    .statusCode()).isEqualTo(400);
            assertThat(send(server, "PUT", path,
                    "{\"name\":\"e\",\"enabled\":true,\"script\":\"\",\"auth\":\"bearer\"}", cookie)
                    .statusCode()).isEqualTo(400);
            assertThat(send(server, "GET", path + "/requests?size=201", null, cookie).statusCode()).isEqualTo(400);
            assertThat(send(server, "GET", path + "/requests?size=0", null, cookie).statusCode()).isEqualTo(400);
            assertThat(send(server, "GET", path + "/requests?page=-1", null, cookie).statusCode()).isEqualTo(400);
            assertThat(send(server, "GET", path + "/requests?page=x", null, cookie).statusCode()).isEqualTo(400);
            String replay = "/api/requests/" + rid + "/replay";
            assertThat(send(server, "POST", replay, "{}", cookie).statusCode()).isEqualTo(400);
            assertThat(send(server, "POST", replay, "{\"mode\":\"other\"}", cookie).statusCode()).isEqualTo(400);
            assertThat(send(server, "POST", replay, "{\"mode\":\"preview\",\"script\":1}", cookie).statusCode())
                    .isEqualTo(400);
            assertThat(history.executionsOf(rid)).isEmpty();
        });
    }

    @Test
    void nulCharactersAre400() {
        Endpoint e = endpoints.create("e");
        JavalinTest.test(app(), (server, client) -> {
            String cookie = login(server);
            String path = "/api/endpoints/" + e.id();
            assertThat(send(server, "POST", "/api/endpoints", "{\"name\":\"a\\u0000b\"}", cookie).statusCode())
                    .isEqualTo(400);
            assertThat(send(server, "PUT", path,
                    "{\"name\":\"a\\u0000\",\"enabled\":true,\"script\":\"\"}", cookie).statusCode())
                    .isEqualTo(400);
            assertThat(send(server, "PUT", path,
                    "{\"name\":\"a\",\"enabled\":true,\"script\":\"x\\u0000\"}", cookie).statusCode())
                    .isEqualTo(400);
            var auth = send(server, "PUT", path, "{\"name\":\"a\",\"enabled\":true,\"script\":\"\","
                    + "\"auth\":{\"type\":\"bearer\",\"token\":\"t\\u0000\"}}", cookie);
            assertThat(auth.statusCode()).isEqualTo(400);
            assertThat(json(auth).get("error").isTextual()).isTrue();
            assertThat(endpoints.find(e.id()).orElseThrow().name()).isEqualTo("e");
        });
    }

    @Test
    void authWithoutSecretIs400() {
        Endpoint e = endpoints.create("e");
        JavalinTest.test(app(), (server, client) -> {
            String cookie = login(server);
            String path = "/api/endpoints/" + e.id();
            String prefix = "{\"name\":\"e\",\"enabled\":true,\"script\":\"\",\"auth\":";
            assertThat(send(server, "PUT", path, prefix + "{\"type\":\"bearer\"}}", cookie).statusCode())
                    .isEqualTo(400);
            assertThat(send(server, "PUT", path, prefix + "{\"type\":\"secretHeader\",\"value\":\"v\"}}", cookie)
                    .statusCode()).isEqualTo(400);
            assertThat(send(server, "PUT", path, prefix + "{\"type\":\"basic\",\"username\":\"u\"}}", cookie)
                    .statusCode()).isEqualTo(400);
            assertThat(send(server, "PUT", path,
                    prefix + "{\"type\":\"hmac\",\"header\":\"X-Sig\",\"secret\":\"s\",\"algorithm\":\"Nope\"}}",
                    cookie).statusCode()).isEqualTo(400);
            assertThat(endpoints.find(e.id()).orElseThrow().auth()).isEqualTo(new AuthConfig.None());

            assertThat(send(server, "PUT", path, prefix + "{\"type\":\"bearer\",\"token\":\"t\"}}", cookie)
                    .statusCode()).isEqualTo(200);
            // the stored secret satisfies the requirement on later updates
            assertThat(send(server, "PUT", path, prefix + "{\"type\":\"bearer\"}}", cookie).statusCode())
                    .isEqualTo(200);
            assertThat(endpoints.find(e.id()).orElseThrow().auth()).isEqualTo(new AuthConfig.Bearer("t"));
        });
    }

    @Test
    void mutationsRequireJsonContentType() {
        Endpoint e = endpoints.create("e");
        JavalinTest.test(app(), (server, client) -> {
            String cookie = login(server);
            try {
                var r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                                URI.create("http://localhost:" + server.port() + "/api/endpoints/" + e.id()))
                        .header("Cookie", cookie).header("Content-Type", "text/plain").DELETE().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(r.statusCode()).isEqualTo(415);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            assertThat(endpoints.find(e.id())).isPresent();
        });
    }

    @Test
    void replayPreviewWithUnsavedScript() {
        Endpoint e = endpoints.create("e");
        Endpoint saved = endpoints.update(new Endpoint(e.id(), e.name(), true, script("saved"), e.auth(),
                e.createdAt(), e.updatedAt()));
        long rid = storedRequest(e.id());
        JavalinTest.test(app(), (server, client) -> {
            String cookie = login(server);
            String body = Json.MAPPER.createObjectNode().put("mode", "preview").put("script", script("inline"))
                    .toString();
            var r = send(server, "POST", "/api/requests/" + rid + "/replay", body, cookie);
            assertThat(r.statusCode()).isEqualTo(200);
            JsonNode out = json(r);
            assertThat(out.get("status").asText()).isEqualTo("success");
            assertThat(out.get("executionId").asLong()).isPositive();
            assertThat(out.get("messages")).hasSize(1);
            assertThat(out.get("messages").get(0).get("message").get("content").asText()).isEqualTo("inline");
            assertThat(out.get("sends")).isEmpty();
            assertThat(r.body()).doesNotContain(FakeDiscord.TOKEN);
            assertThat(discord.contents).isEmpty();
            assertThat(endpoints.find(e.id()).orElseThrow().script()).isEqualTo(saved.script());
            assertThat(history.findRequest(rid).orElseThrow().status()).isEqualTo(RequestStatus.PENDING);
            assertThat(history.executionsOf(rid)).singleElement()
                    .satisfies(x -> assertThat(x.mode()).isEqualTo(Execution.Mode.PREVIEW));

            JsonNode detail = json(send(server, "GET", "/api/requests/" + rid, null, cookie));
            assertThat(detail.get("executions")).hasSize(1);
            assertThat(detail.get("executions").get(0).get("mode").asText()).isEqualTo("preview");
        });
    }

    @Test
    void replaySendMarksDone() {
        Endpoint e = endpoints.create("e");
        endpoints.update(new Endpoint(e.id(), e.name(), true, script("saved"), e.auth(), e.createdAt(),
                e.updatedAt()));
        long rid = storedRequest(e.id());
        history.setStatus(rid, RequestStatus.FAILED);
        JavalinTest.test(app(), (server, client) -> {
            String cookie = login(server);
            var r = send(server, "POST", "/api/requests/" + rid + "/replay", "{\"mode\":\"send\"}", cookie);
            assertThat(r.statusCode()).isEqualTo(200);
            JsonNode out = json(r);
            assertThat(out.get("status").asText()).isEqualTo("success");
            assertThat(out.get("sends")).hasSize(1);
            assertThat(out.get("error").isNull()).isTrue();
            assertThat(discord.contents).containsExactly("saved");
            assertThat(history.findRequest(rid).orElseThrow().status()).isEqualTo(RequestStatus.DONE);
            assertThat(history.executionsOf(rid)).singleElement()
                    .satisfies(x -> assertThat(x.mode()).isEqualTo(Execution.Mode.REPLAY));
        });
    }

    @Test
    void replayScriptErrorKeepsStatus() {
        Endpoint e = endpoints.create("e");
        long rid = storedRequest(e.id());
        history.setStatus(rid, RequestStatus.FAILED);
        JavalinTest.test(app(), (server, client) -> {
            String cookie = login(server);
            String body = "{\"mode\":\"send\",\"script\":\"function handle(q){ throw new Error('boom'); }\"}";
            JsonNode out = json(send(server, "POST", "/api/requests/" + rid + "/replay", body, cookie));
            assertThat(out.get("status").asText()).isEqualTo("script_error");
            assertThat(out.get("error").asText()).contains("boom");
            assertThat(history.findRequest(rid).orElseThrow().status()).isEqualTo(RequestStatus.FAILED);
            assertThat(discord.contents).isEmpty();
        });
    }

    @Test
    void replaySendOnPendingClaimsTheRequest() {
        Endpoint e = endpoints.create("e");
        endpoints.update(new Endpoint(e.id(), e.name(), true, script("saved"), e.auth(), e.createdAt(),
                e.updatedAt()));
        long rid = storedRequest(e.id());
        JavalinTest.test(app(), (server, client) -> {
            String cookie = login(server);
            var r = send(server, "POST", "/api/requests/" + rid + "/replay", "{\"mode\":\"send\"}", cookie);
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(json(r).get("status").asText()).isEqualTo("success");
            assertThat(discord.contents).containsExactly("saved");
            // no longer pending: a later drain cannot deliver it a second time
            assertThat(history.findRequest(rid).orElseThrow().status()).isEqualTo(RequestStatus.DONE);
            assertThat(history.hasPending(e.id())).isFalse();
        });
    }

    @Test
    void replaySendOnPendingScriptErrorMarksFailed() {
        Endpoint e = endpoints.create("e");
        long rid = storedRequest(e.id());
        JavalinTest.test(app(), (server, client) -> {
            String cookie = login(server);
            String body = "{\"mode\":\"send\",\"script\":\"function handle(q){ throw new Error('boom'); }\"}";
            var r = send(server, "POST", "/api/requests/" + rid + "/replay", body, cookie);
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(history.findRequest(rid).orElseThrow().status()).isEqualTo(RequestStatus.FAILED);
        });
    }

    @Test
    void replaySendOnPendingClaimedByDrainIs409() {
        Endpoint e = endpoints.create("e");
        endpoints.update(new Endpoint(e.id(), e.name(), true, script("saved"), e.auth(), e.createdAt(),
                e.updatedAt()));
        long rid = storedRequest(e.id());
        // simulates a drain claiming the row between the read and the claim: the claim updates no row
        jdbi().useHandle(h -> {
            h.execute("CREATE FUNCTION test_block_claim() RETURNS trigger LANGUAGE plpgsql AS "
                    + "$$ BEGIN RETURN NULL; END $$");
            h.execute("CREATE TRIGGER test_block_claim BEFORE UPDATE ON requests FOR EACH ROW "
                    + "WHEN (NEW.status = 'processing') EXECUTE FUNCTION test_block_claim()");
        });
        try {
            JavalinTest.test(app(), (server, client) -> {
                String cookie = login(server);
                var r = send(server, "POST", "/api/requests/" + rid + "/replay", "{\"mode\":\"send\"}", cookie);
                assertThat(r.statusCode()).isEqualTo(409);
                assertThat(json(r).get("error").asText()).isEqualTo("request is being processed");
                assertThat(discord.contents).isEmpty();
                assertThat(history.executionsOf(rid)).isEmpty();

                // preview never claims and stays allowed
                var p = send(server, "POST", "/api/requests/" + rid + "/replay", "{\"mode\":\"preview\"}", cookie);
                assertThat(p.statusCode()).isEqualTo(200);
            });
        } finally {
            jdbi().useHandle(h -> {
                h.execute("DROP TRIGGER test_block_claim ON requests");
                h.execute("DROP FUNCTION test_block_claim()");
            });
        }
    }
}
