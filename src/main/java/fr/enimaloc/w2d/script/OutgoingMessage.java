package fr.enimaloc.w2d.script;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Set;

/**
 * One Discord webhook call; {@code message} is null for DELETE. After a successful send, {@code saveId} stores the
 * returned message id under that store key and {@code forget} removes that key (both optional).
 */
public record OutgoingMessage(String method, String url, JsonNode message, String saveId, String forget) {
    public static final Set<String> METHODS = Set.of("POST", "PATCH", "DELETE");

    public OutgoingMessage {
        method = method == null ? "POST" : method.toUpperCase(Locale.ROOT);
    }

    public OutgoingMessage(String method, String url, JsonNode message) {
        this(method, url, message, null, null);
    }

    public OutgoingMessage(String url, JsonNode message) {
        this("POST", url, message);
    }
}
