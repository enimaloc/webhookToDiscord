package fr.enimaloc.w2d.processing;

import fr.enimaloc.w2d.endpoint.Endpoint;
import fr.enimaloc.w2d.endpoint.EndpointRepository;
import fr.enimaloc.w2d.history.HistoryRepository;
import fr.enimaloc.w2d.history.IncomingRequest.RequestStatus;
import fr.enimaloc.w2d.script.ScriptRunner;
import fr.enimaloc.w2d.store.StoreRepository;
import fr.enimaloc.w2d.support.PostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EntryProcessorTest extends PostgresTest {
    private static final String SCRIPT = """
            function handle(q) {
              if (q.json.n === %s) throw new Error('boom');
              return [{ url: "%s", message: { content: String(q.json.n) } }];
            }
            """;

    private EndpointRepository endpoints;
    private HistoryRepository history;
    private ScriptRunner runner;
    private FakeDiscord discord;
    private final List<ExecutorService> executors = new ArrayList<>();
    private String endpointId;

    @BeforeEach
    void setUp() {
        endpoints = new EndpointRepository(jdbi());
        history = new HistoryRepository(jdbi());
        runner = new ScriptRunner(Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        executors.forEach(ExecutorService::shutdownNow);
        runner.close();
        if (discord != null) {
            discord.close();
        }
    }

    private void endpoint(Integer failOn) {
        Endpoint e = endpoints.create("e");
        endpoints.update(new Endpoint(e.id(), e.name(), e.enabled(),
                SCRIPT.formatted(failOn == null ? "undefined" : failOn, FakeDiscord.URL),
                e.auth(), e.createdAt(), e.updatedAt()));
        endpointId = e.id();
    }

    private EntryProcessor processor(long discordDelayMs) {
        return processor(discordDelayMs, d -> Thread.sleep(d));
    }

    private EntryProcessor processor(long discordDelayMs, EntryProcessor.Sleeper sleeper) {
        if (discord == null) {
            discord = new FakeDiscord(204, discordDelayMs);
        }
        ExecutorService ex = Executors.newSingleThreadExecutor();
        executors.add(ex);
        Pipeline pipeline = new Pipeline(endpoints, history, runner, discord.sender(), new StoreRepository(db.jdbi()));
        return new EntryProcessor(jdbi(), endpoints, history, pipeline, ex, sleeper);
    }

    private long insert(int n) {
        return history.insertPending(endpointId, "POST", Map.of(), "",
                ("{\"n\":" + n + "}").getBytes(StandardCharsets.UTF_8), Set.of());
    }

    private RequestStatus status(long id) {
        return history.findRequest(id).orElseThrow().status();
    }

    @Test
    void processesInOrder() {
        endpoint(null);
        EntryProcessor p = processor(0);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(insert(i));
        }

        p.drain(endpointId);

        assertThat(discord.contents).containsExactly("0", "1", "2", "3", "4");
        assertThat(ids).allSatisfy(id -> assertThat(status(id)).isEqualTo(RequestStatus.DONE));
        assertThat(history.hasPending(endpointId)).isFalse();
    }

    @Test
    void failureDoesNotBlockNextRequest() {
        endpoint(1);
        EntryProcessor p = processor(0);
        long r0 = insert(0), r1 = insert(1), r2 = insert(2);

        p.drain(endpointId);

        assertThat(status(r0)).isEqualTo(RequestStatus.DONE);
        assertThat(status(r1)).isEqualTo(RequestStatus.FAILED);
        assertThat(status(r2)).isEqualTo(RequestStatus.DONE);
        assertThat(discord.contents).containsExactly("0", "2");
    }

    @Test
    void twoProcessorsPreserveOrder() throws InterruptedException {
        endpoint(null);
        EntryProcessor a = processor(50);
        EntryProcessor b = processor(50);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(insert(i));
        }
        a.trigger(endpointId);
        // A now holds the lock and is mid-pipeline: every trigger below genuinely overlaps with it
        assertThat(discord.firstRequest.await(10, TimeUnit.SECONDS)).isTrue();
        for (int i = 5; i < 10; i++) {
            ids.add(insert(i));
            (i % 2 == 0 ? a : b).trigger(endpointId);
            b.trigger(endpointId);
        }

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!ids.stream().allMatch(id -> status(id) == RequestStatus.DONE)) {
            assertThat(System.nanoTime()).as("all requests processed within 10 s").isLessThan(deadline);
            Thread.sleep(50);
        }

        assertThat(discord.contents).containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertThat(discord.maxInFlight.get()).as("never two sends at once").isEqualTo(1);
    }

    @Test
    void resendIsPacedAndNewRequestsWaitBehind() {
        endpoint(null);
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        EntryProcessor p = processor(0, d -> events.add("sleep " + d.toMillis() + " after " + discord.contents));
        insert(0);
        insert(1);
        p.drain(endpointId);
        assertThat(history.requeue(endpointId, false, 250)).isEqualTo(2);
        insert(2);

        p.drain(endpointId);

        assertThat(discord.contents).containsExactly("0", "1", "0", "1", "2");
        // a pause after each resent request, none after the new one (no pacing, nothing left)
        assertThat(events).containsExactly("sleep 250 after [0, 1, 0]", "sleep 250 after [0, 1, 0, 1]");
    }

    @Test
    void interruptedPauseLeavesTheRestPending() {
        endpoint(null);
        EntryProcessor p = processor(0, d -> {
            throw new InterruptedException();
        });
        insert(0);
        insert(1);
        p.drain(endpointId);
        history.requeue(endpointId, false, 1000);

        p.drain(endpointId);

        assertThat(Thread.interrupted()).isTrue();
        assertThat(discord.contents).containsExactly("0", "1", "0");
        assertThat(history.hasPending(endpointId)).isTrue();
    }
}
