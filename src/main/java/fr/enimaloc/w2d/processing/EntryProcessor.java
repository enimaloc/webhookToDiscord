package fr.enimaloc.w2d.processing;

import fr.enimaloc.w2d.endpoint.Endpoint;
import fr.enimaloc.w2d.endpoint.EndpointRepository;
import fr.enimaloc.w2d.history.Execution;
import fr.enimaloc.w2d.history.HistoryRepository;
import fr.enimaloc.w2d.history.IncomingRequest;
import fr.enimaloc.w2d.history.IncomingRequest.RequestStatus;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Processes the pending requests of an entry one at a time, in arrival order, across all pods.
 * Mutual exclusion uses a PostgreSQL session-level advisory lock held on a dedicated connection.
 */
public final class EntryProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(EntryProcessor.class);
    private static final int LOCK_NAMESPACE = 7331;

    private final Jdbi jdbi;
    private final EndpointRepository endpoints;
    private final HistoryRepository history;
    private final Pipeline pipeline;
    private final ExecutorService executor;

    public EntryProcessor(Jdbi jdbi, EndpointRepository endpoints, HistoryRepository history, Pipeline pipeline,
                          ExecutorService executor) {
        this.jdbi = jdbi;
        this.endpoints = endpoints;
        this.history = history;
        this.pipeline = pipeline;
        this.executor = executor;
    }

    /** Schedules a drain of the entry and returns immediately. */
    public void trigger(String endpointId) {
        try {
            executor.execute(() -> {
                try {
                    drain(endpointId);
                } catch (RuntimeException e) {
                    LOG.error("Draining entry {} failed", endpointId, e);
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.warn("Could not schedule drain of entry {}: executor rejected the task", endpointId);
        }
    }

    /** Processes every pending request of the entry synchronously, unless another worker already holds it. */
    public void drain(String endpointId) {
        while (true) {
            Drain result = jdbi.withHandle(h -> drainLocked(h, endpointId));
            // NOT_LOCKED: the current holder re-checks for pending work after it unlocks, so it will pick ours up.
            // DONE: re-check to close the race with a request inserted after our last claim but before unlock.
            if (result != Drain.DONE || !history.hasPending(endpointId)) {
                return;
            }
        }
    }

    private enum Drain { NOT_LOCKED, DONE, ENTRY_GONE }

    private Drain drainLocked(Handle h, String endpointId) {
        // Session-scoped lock: acquire, claim and release must all happen on this same handle (connection).
        boolean locked = h.select("SELECT pg_try_advisory_lock(?, hashtext(?))", LOCK_NAMESPACE, endpointId)
                .mapTo(Boolean.class).one();
        if (!locked) {
            return Drain.NOT_LOCKED;
        }
        try {
            Optional<IncomingRequest> next;
            while ((next = history.claimNextPending(h, endpointId)).isPresent()) {
                IncomingRequest req = next.get();
                Optional<Endpoint> endpoint = endpoints.find(endpointId);
                if (endpoint.isEmpty()) {
                    // entry deleted: its requests are removed by cascade
                    return Drain.ENTRY_GONE;
                }
                process(req, endpoint.get());
            }
            return Drain.DONE;
        } finally {
            h.select("SELECT pg_advisory_unlock(?, hashtext(?))", LOCK_NAMESPACE, endpointId)
                    .mapTo(Boolean.class).one();
        }
    }

    private void process(IncomingRequest req, Endpoint endpoint) {
        RequestStatus status;
        try {
            PipelineOutcome outcome = pipeline.execute(req, endpoint.script(), Execution.Mode.NORMAL);
            status = outcome.status() == Execution.Status.SUCCESS ? RequestStatus.DONE : RequestStatus.FAILED;
        } catch (RuntimeException e) {
            LOG.error("Processing request {} of entry {} failed", req.id(), endpoint.id(), e);
            status = RequestStatus.FAILED;
        }
        history.setStatus(req.id(), status);
    }
}
