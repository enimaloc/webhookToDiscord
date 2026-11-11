package fr.enimaloc.w2d.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import fr.enimaloc.w2d.history.IncomingRequest.RequestStatus;
import fr.enimaloc.w2d.json.Json;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class HistoryRepository {
    private static final String REQ_COLUMNS =
            "id, endpoint_id, received_at, method, headers::text AS headers, query, body, status, resend_delay_ms";
    private static final String REQ_COLUMNS_NO_BODY =
            "id, endpoint_id, received_at, method, headers::text AS headers, query, ''::bytea AS body, status, "
                    + "resend_delay_ms";
    private static final String EXEC_COLUMNS =
            "id, request_id, started_at, mode, status, logs, messages::text AS messages, "
                    + "send_results::text AS send_results, error";
    private static final TypeReference<Map<String, List<String>>> HEADERS_TYPE = new TypeReference<>() {
    };

    private final Jdbi jdbi;

    public HistoryRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public long insertPending(String endpointId, String method, Map<String, List<String>> headers, String query,
                              byte[] body, Set<String> maskedHeaders) {
        Set<String> masks = maskedHeaders.stream().map(m -> m.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Map<String, List<String>> stored = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            String key = name.toLowerCase(Locale.ROOT);
            boolean masked = key.equals("authorization") || masks.contains(key);
            stored.put(key, masked ? List.of("***") : List.copyOf(values));
        });
        return jdbi.withHandle(h -> h.createQuery(
                        "INSERT INTO requests (endpoint_id, method, headers, query, body, status) "
                                + "VALUES (:endpoint, :method, CAST(:headers AS jsonb), :query, :body, 'pending') RETURNING id")
                .bind("endpoint", endpointId).bind("method", method).bind("headers", toJson(stored))
                .bind("query", query).bind("body", body)
                .mapTo(Long.class).one());
    }

    /** Claims the oldest pending request; runs on the given handle so it shares the caller's connection. */
    public Optional<IncomingRequest> claimNextPending(Handle h, String endpointId) {
        return h.createQuery("UPDATE requests SET status = 'processing' WHERE status = 'pending' AND id = ("
                        + "SELECT id FROM requests WHERE endpoint_id = :endpoint AND status = 'pending' "
                        + "ORDER BY id LIMIT 1 FOR UPDATE) RETURNING " + REQ_COLUMNS)
                .bind("endpoint", endpointId).map(HistoryRepository::mapRequest).findOne();
    }

    /** Moves the request from pending to processing; false if it was not pending (e.g. a drain claimed it). */
    public boolean claimPending(long requestId) {
        return jdbi.withHandle(h -> h.createUpdate(
                        "UPDATE requests SET status = 'processing' WHERE id = :id AND status = 'pending'")
                .bind("id", requestId).execute()) == 1;
    }

    /**
     * Puts the entry's finished requests (done and failed, or failed only) back to pending so the processor sends
     * them again in arrival order, pausing {@code delayMs} after each; pending and processing requests are left
     * alone. Returns how many were requeued.
     */
    public int requeue(String endpointId, boolean onlyFailed, int delayMs) {
        return jdbi.withHandle(h -> h.createUpdate("UPDATE requests SET status = 'pending', resend_delay_ms = :delay "
                        + "WHERE endpoint_id = :endpoint "
                        + "AND status IN (" + (onlyFailed ? "'failed'" : "'done','failed'") + ")")
                .bind("endpoint", endpointId).bind("delay", delayMs).execute());
    }

    public boolean hasPending(String endpointId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT EXISTS (SELECT 1 FROM requests WHERE endpoint_id = :endpoint AND status = 'pending')")
                .bind("endpoint", endpointId).mapTo(Boolean.class).one());
    }

    public void setStatus(long requestId, RequestStatus status) {
        jdbi.useHandle(h -> h.createUpdate("UPDATE requests SET status = :status WHERE id = :id")
                .bind("status", status.name().toLowerCase(Locale.ROOT)).bind("id", requestId).execute());
    }

    public long addExecution(long requestId, Execution.Mode mode, Execution.Status status, String logs,
                             JsonNode messages, JsonNode sendResults, String error) {
        return jdbi.withHandle(h -> h.createQuery(
                        "INSERT INTO executions (request_id, mode, status, logs, messages, send_results, error) "
                                + "VALUES (:request, :mode, :status, :logs, CAST(:messages AS jsonb), "
                                + "CAST(:sendResults AS jsonb), :error) RETURNING id")
                .bind("request", requestId)
                .bind("mode", mode.name().toLowerCase(Locale.ROOT))
                .bind("status", status.name().toLowerCase(Locale.ROOT))
                .bind("logs", logs)
                .bind("messages", messages == null ? null : toJson(messages))
                .bind("sendResults", sendResults == null ? null : toJson(sendResults))
                .bind("error", error)
                .mapTo(Long.class).one());
    }

    public Optional<IncomingRequest> findRequest(long id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT " + REQ_COLUMNS + " FROM requests WHERE id = :id")
                .bind("id", id).map(HistoryRepository::mapRequest).findOne());
    }

    public List<Execution> executionsOf(long requestId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT " + EXEC_COLUMNS + " FROM executions WHERE request_id = :id ORDER BY id")
                .bind("id", requestId).map(HistoryRepository::mapExecution).list());
    }

    /** Newest first; bodies are not loaded (empty arrays). Pages are 0-based. */
    public List<IncomingRequest> page(String endpointId, int page, int size) {
        return jdbi.withHandle(h -> h.createQuery("SELECT " + REQ_COLUMNS_NO_BODY
                        + " FROM requests WHERE endpoint_id = :endpoint ORDER BY id DESC LIMIT :size OFFSET :offset")
                .bind("endpoint", endpointId).bind("size", size).bind("offset", (long) page * size)
                .map(HistoryRepository::mapRequest).list());
    }

    public Optional<Instant> lastReceived(String endpointId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT max(received_at) FROM requests WHERE endpoint_id = :endpoint")
                .bind("endpoint", endpointId)
                .map((rs, ctx) -> Optional.ofNullable(rs.getTimestamp(1)).map(Timestamp::toInstant))
                .one());
    }

    public int purgeOlderThan(Instant cutoff) {
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM requests WHERE received_at < :cutoff")
                .bind("cutoff", Timestamp.from(cutoff)).execute());
    }

    private static String toJson(Object value) {
        try {
            return Json.MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static JsonNode readNode(String json) throws SQLException {
        if (json == null) {
            return null;
        }
        try {
            return Json.MAPPER.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new SQLException("Invalid json", ex);
        }
    }

    private static IncomingRequest mapRequest(ResultSet rs, StatementContext ctx) throws SQLException {
        try {
            return new IncomingRequest(rs.getLong("id"), rs.getString("endpoint_id"),
                    rs.getTimestamp("received_at").toInstant(), rs.getString("method"),
                    Json.MAPPER.readValue(rs.getString("headers"), HEADERS_TYPE), rs.getString("query"),
                    rs.getBytes("body"),
                    RequestStatus.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
                    rs.getInt("resend_delay_ms"));
        } catch (JsonProcessingException ex) {
            throw new SQLException("Invalid headers json", ex);
        }
    }

    private static Execution mapExecution(ResultSet rs, StatementContext ctx) throws SQLException {
        return new Execution(rs.getLong("id"), rs.getLong("request_id"),
                rs.getTimestamp("started_at").toInstant(),
                Execution.Mode.valueOf(rs.getString("mode").toUpperCase(Locale.ROOT)),
                Execution.Status.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
                rs.getString("logs"), readNode(rs.getString("messages")),
                readNode(rs.getString("send_results")), rs.getString("error"));
    }
}
