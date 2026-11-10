package fr.enimaloc.w2d.history;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record IncomingRequest(long id, String endpointId, Instant receivedAt, String method,
                              Map<String, List<String>> headers, String query, byte[] body,
                              RequestStatus status) {
    public enum RequestStatus { PENDING, PROCESSING, DONE, FAILED }
}
