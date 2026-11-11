package fr.enimaloc.w2d.history;

import fr.enimaloc.w2d.endpoint.Endpoint;
import fr.enimaloc.w2d.endpoint.EndpointRepository;
import fr.enimaloc.w2d.history.IncomingRequest.RequestStatus;
import fr.enimaloc.w2d.json.Json;
import fr.enimaloc.w2d.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryRepositoryTest extends PostgresTest {
    private HistoryRepository repo;
    private String endpointId;

    @BeforeEach
    void setUp() {
        repo = new HistoryRepository(jdbi());
        Endpoint e = new EndpointRepository(jdbi()).create("e");
        endpointId = e.id();
    }

    private long insert() {
        return repo.insertPending(endpointId, "POST", Map.of(), "", new byte[0], Set.of());
    }

    @Test
    void sensitiveHeadersAreMasked() {
        long id = repo.insertPending(endpointId, "POST",
                Map.of("Authorization", List.of("Bearer x"), "X-Secret", List.of("y"), "X-Other", List.of("z")),
                "a=1", new byte[0], Set.of("x-secret"));
        Map<String, List<String>> h = repo.findRequest(id).orElseThrow().headers();
        assertThat(h).containsEntry("authorization", List.of("***"))
                .containsEntry("x-secret", List.of("***"))
                .containsEntry("x-other", List.of("z"));
    }

    @Test
    void claimIsFifoAndExclusive() {
        long a = insert(), b = insert(), c = insert();
        jdbi().useHandle(h -> {
            assertThat(repo.claimNextPending(h, endpointId)).get().extracting(IncomingRequest::id).isEqualTo(a);
            assertThat(repo.claimNextPending(h, endpointId)).get().extracting(IncomingRequest::id).isEqualTo(b);
            assertThat(repo.claimNextPending(h, endpointId)).get().extracting(IncomingRequest::id).isEqualTo(c);
            assertThat(repo.claimNextPending(h, endpointId)).isEmpty();
        });
        for (long id : new long[]{a, b, c}) {
            assertThat(repo.findRequest(id).orElseThrow().status()).isEqualTo(RequestStatus.PROCESSING);
        }
    }

    @Test
    void bodyBytesRoundTrip() {
        byte[] body = {(byte) 0xff, 0x00, 0x41};
        long id = repo.insertPending(endpointId, "POST", Map.of(), "", body, Set.of());
        assertThat(repo.findRequest(id).orElseThrow().body()).isEqualTo(body);
        assertThat(repo.page(endpointId, 0, 10)).singleElement().satisfies(r -> assertThat(r.body()).isEmpty());
        assertThat(repo.lastReceived(endpointId)).isPresent();
    }

    @Test
    void executionsAttached() throws Exception {
        long id = insert();
        var msgs = Json.MAPPER.readTree("[{\"content\":\"hi\"}]");
        repo.addExecution(id, Execution.Mode.NORMAL, Execution.Status.SUCCESS, "log1", msgs, null, null);
        repo.addExecution(id, Execution.Mode.REPLAY, Execution.Status.SCRIPT_ERROR, "log2", null, null, "boom");
        List<Execution> list = repo.executionsOf(id);
        assertThat(list).extracting(Execution::logs).containsExactly("log1", "log2");
        assertThat(list.get(0).messages()).isEqualTo(msgs);
        assertThat(list.get(1).messages()).isNull();
        assertThat(list.get(1).error()).isEqualTo("boom");
        assertThat(list.get(1).mode()).isEqualTo(Execution.Mode.REPLAY);
    }

    @Test
    void purgeRemovesOldOnly() {
        long old = insert();
        long recent = insert();
        jdbi().useHandle(h -> h.createUpdate(
                "UPDATE requests SET received_at = now() - interval '31 days' WHERE id = :id").bind("id", old).execute());
        assertThat(new RetentionJob(repo, null, 30).runOnce(Instant.now())).isEqualTo(1);
        assertThat(repo.findRequest(old)).isEqualTo(Optional.empty());
        assertThat(repo.findRequest(recent)).isPresent();
    }

    @Test
    void claimPendingOnlyOnce() {
        long id = insert();
        assertThat(repo.claimPending(id)).isTrue();
        assertThat(repo.findRequest(id).orElseThrow().status()).isEqualTo(RequestStatus.PROCESSING);
        assertThat(repo.claimPending(id)).isFalse();
        repo.setStatus(id, RequestStatus.FAILED);
        assertThat(repo.claimPending(id)).isFalse();
        assertThat(repo.findRequest(id).orElseThrow().status()).isEqualTo(RequestStatus.FAILED);
    }

    @Test
    void requeueLeavesUnfinishedRequestsAlone() {
        long done = insert();
        long failed = insert();
        long processing = insert();
        long pending = insert();
        repo.setStatus(done, RequestStatus.DONE);
        repo.setStatus(failed, RequestStatus.FAILED);
        repo.setStatus(processing, RequestStatus.PROCESSING);

        assertThat(repo.requeue(endpointId, true, 100)).isEqualTo(1);
        assertThat(repo.findRequest(done).orElseThrow().status()).isEqualTo(RequestStatus.DONE);
        // the failed one is already pending again: only the done one is left to requeue
        assertThat(repo.requeue(endpointId, false, 200)).isEqualTo(1);
        assertThat(repo.findRequest(done).orElseThrow().resendDelayMs()).isEqualTo(200);
        assertThat(repo.findRequest(processing).orElseThrow().status()).isEqualTo(RequestStatus.PROCESSING);
        assertThat(repo.findRequest(pending).orElseThrow().resendDelayMs()).isZero();
    }
}
