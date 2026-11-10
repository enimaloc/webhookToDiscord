package fr.enimaloc.w2d.history;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record Execution(long id, long requestId, Instant startedAt, Mode mode, Status status, String logs,
                        JsonNode messages, JsonNode sendResults, String error) {
    public enum Mode { NORMAL, PREVIEW, REPLAY }

    public enum Status { SUCCESS, SCRIPT_ERROR, SEND_ERROR }
}
