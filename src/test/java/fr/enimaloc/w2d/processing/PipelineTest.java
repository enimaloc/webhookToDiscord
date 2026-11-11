package fr.enimaloc.w2d.processing;

import com.fasterxml.jackson.databind.JsonNode;
import fr.enimaloc.w2d.endpoint.EndpointRepository;
import fr.enimaloc.w2d.history.Execution;
import fr.enimaloc.w2d.history.HistoryRepository;
import fr.enimaloc.w2d.history.IncomingRequest;
import fr.enimaloc.w2d.script.ScriptRunner;
import fr.enimaloc.w2d.store.StoreRepository;
import fr.enimaloc.w2d.support.PostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineTest extends PostgresTest {
    private static final String SCRIPT = """
            function handle(q) {
              return [{ url: "%s", message: { content: "hello" } }];
            }
            """.formatted(FakeDiscord.URL);

    private EndpointRepository endpoints;
    private HistoryRepository history;
    private ScriptRunner runner;
    private FakeDiscord discord;
    private String endpointId;

    @BeforeEach
    void setUp() {
        endpoints = new EndpointRepository(jdbi());
        history = new HistoryRepository(jdbi());
        runner = new ScriptRunner(Duration.ofSeconds(5));
        endpointId = endpoints.create("e").id();
    }

    @AfterEach
    void tearDown() {
        runner.close();
        if (discord != null) {
            discord.close();
        }
    }

    private Pipeline pipeline(int status) {
        discord = new FakeDiscord(status, 0);
        return new Pipeline(endpoints, history, runner, discord.sender(), new StoreRepository(db.jdbi()));
    }

    private IncomingRequest request() {
        long id = history.insertPending(endpointId, "POST", Map.of(), "",
                "{}".getBytes(StandardCharsets.UTF_8), Set.of());
        return history.findRequest(id).orElseThrow();
    }

    @Test
    void previewSendsNothing() {
        Pipeline p = pipeline(204);
        IncomingRequest req = request();
        PipelineOutcome out = p.execute(req, SCRIPT, Execution.Mode.PREVIEW);

        assertThat(out.status()).isEqualTo(Execution.Status.SUCCESS);
        assertThat(out.sends()).isEmpty();
        assertThat(out.script().messages()).hasSize(1);
        assertThat(discord.contents).isEmpty();
        List<Execution> execs = history.executionsOf(req.id());
        assertThat(execs).hasSize(1);
        assertThat(execs.get(0).id()).isEqualTo(out.executionId());
        assertThat(execs.get(0).mode()).isEqualTo(Execution.Mode.PREVIEW);
        assertThat(execs.get(0).status()).isEqualTo(Execution.Status.SUCCESS);
    }

    @Test
    void sendErrorRecorded() {
        Pipeline p = pipeline(400);
        IncomingRequest req = request();
        PipelineOutcome out = p.execute(req, SCRIPT, Execution.Mode.NORMAL);

        assertThat(out.status()).isEqualTo(Execution.Status.SEND_ERROR);
        Execution exec = history.executionsOf(req.id()).get(0);
        assertThat(exec.status()).isEqualTo(Execution.Status.SEND_ERROR);
        assertThat(exec.mode()).isEqualTo(Execution.Mode.NORMAL);
        assertThat(exec.sendResults().get(0).get("status").asInt()).isEqualTo(400);
        assertThat(exec.sendResults().toString()).doesNotContain(FakeDiscord.TOKEN);
    }

    @Test
    void messagesStoredMasked() {
        Pipeline p = pipeline(204);
        IncomingRequest req = request();
        PipelineOutcome out = p.execute(req, SCRIPT, Execution.Mode.NORMAL);

        assertThat(out.status()).isEqualTo(Execution.Status.SUCCESS);
        assertThat(discord.contents).containsExactly("hello");
        Execution exec = history.executionsOf(req.id()).get(0);
        assertThat(exec.messages().toString()).doesNotContain(FakeDiscord.TOKEN);
        assertThat(exec.messages().get(0).get("url").asText())
                .isEqualTo("https://discord.com/api/webhooks/1/***");
        assertThat(exec.messages().get(0).get("message").get("content").asText()).isEqualTo("hello");
    }

    @Test
    void scriptErrorRecorded() {
        Pipeline p = pipeline(204);
        IncomingRequest req = request();
        PipelineOutcome out = p.execute(req, "function handle(q) { throw new Error('nope'); }",
                Execution.Mode.NORMAL);

        assertThat(out.status()).isEqualTo(Execution.Status.SCRIPT_ERROR);
        assertThat(discord.contents).isEmpty();
        Execution exec = history.executionsOf(req.id()).get(0);
        assertThat(exec.status()).isEqualTo(Execution.Status.SCRIPT_ERROR);
        assertThat(exec.error()).contains("nope");
    }

    @Test
    void nulCharactersAreStoredReplaced() {
        Pipeline p = pipeline(204);
        long rid = history.insertPending(endpointId, "POST", Map.of(), "",
                new byte[]{'a', 0, 'b'}, Set.of());
        IncomingRequest req = history.findRequest(rid).orElseThrow();
        String script = """
                function handle(r) {
                  console.log(r.body);
                  var o = {};
                  o[r.body] = r.body;
                  return [{ url: "%s", message: { content: r.body, embeds: [o] } }];
                }
                """.formatted(FakeDiscord.URL);
        PipelineOutcome out = p.execute(req, script, Execution.Mode.NORMAL);

        assertThat(out.status()).isEqualTo(Execution.Status.SUCCESS);
        assertThat(discord.contents).containsExactly("a\u0000b");
        assertThat(out.script().logs()).contains("a\uFFFDb").doesNotContain("\u0000");
        Execution exec = history.executionsOf(req.id()).get(0);
        assertThat(exec.id()).isEqualTo(out.executionId());
        assertThat(exec.status()).isEqualTo(Execution.Status.SUCCESS);
        assertThat(exec.logs()).contains("a\uFFFDb").doesNotContain("\u0000");
        JsonNode message = exec.messages().get(0).get("message");
        assertThat(message.get("content").asText()).isEqualTo("a\uFFFDb");
        assertThat(message.get("embeds").get(0).get("a\uFFFDb").asText()).isEqualTo("a\uFFFDb");
    }

    private static final String EDITING_SCRIPT = """
            function handle(r) {
              const id = r.store.k;
              if (r.json.done) return [{ method: "DELETE", url: "%1$s/messages/" + id, forget: "k" }];
              return id
                ? [{ method: "PATCH", url: "%1$s/messages/" + id, message: { content: "edit" } }]
                : [{ url: "%1$s?wait=true", message: { content: "new" }, saveId: "k" }];
            }
            """.formatted(FakeDiscord.URL);

    @Test
    void savedIdIsReusedByTheNextRun() {
        Pipeline p = pipeline(204);
        StoreRepository store = new StoreRepository(db.jdbi());

        p.execute(request(), EDITING_SCRIPT, Execution.Mode.PREVIEW);
        assertThat(store.all(endpointId)).isEmpty();

        PipelineOutcome first = p.execute(request(), EDITING_SCRIPT, Execution.Mode.NORMAL);
        assertThat(first.status()).isEqualTo(Execution.Status.SUCCESS);
        assertThat(discord.contents).containsExactly("new");
        assertThat(store.all(endpointId)).containsExactly(Map.entry("k", "1"));

        PipelineOutcome second = p.execute(request(), EDITING_SCRIPT, Execution.Mode.NORMAL);
        assertThat(second.status()).isEqualTo(Execution.Status.SUCCESS);
        assertThat(discord.patches).containsExactly("1:edit");
        assertThat(discord.contents).containsExactly("new");
        assertThat(second.sends().get(0).message().get("id").asText()).isEqualTo("1");
    }

    @Test
    void failedSendKeepsTheStoreUntouched() {
        Pipeline p = pipeline(500);
        StoreRepository store = new StoreRepository(db.jdbi());
        store.put(endpointId, "k", "7");
        long rid = history.insertPending(endpointId, "POST", Map.of(), "",
                "{\"done\":true}".getBytes(StandardCharsets.UTF_8), Set.of());
        // DELETE route is not served by FakeDiscord, so the send fails
        PipelineOutcome out = p.execute(history.findRequest(rid).orElseThrow(), EDITING_SCRIPT, Execution.Mode.NORMAL);
        assertThat(out.status()).isEqualTo(Execution.Status.SEND_ERROR);
        assertThat(store.all(endpointId)).containsExactly(Map.entry("k", "7"));
    }
}
