package fr.enimaloc.w2d.history;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** {@code resendDelayMs}: pause after this request when it was requeued from the history, 0 otherwise. */
public record IncomingRequest(long id, String endpointId, Instant receivedAt, String method,
                              Map<String, List<String>> headers, String query, byte[] body,
                              RequestStatus status, int resendDelayMs) {
    public enum RequestStatus { PENDING, PROCESSING, DONE, FAILED }

    public IncomingRequest(long id, String endpointId, Instant receivedAt, String method,
                           Map<String, List<String>> headers, String query, byte[] body, RequestStatus status) {
        this(id, endpointId, receivedAt, method, headers, query, body, status, 0);
    }
}
