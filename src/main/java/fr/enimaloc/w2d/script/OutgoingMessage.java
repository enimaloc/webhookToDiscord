package fr.enimaloc.w2d.script;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Set;

/** One Discord webhook call; {@code message} is null for DELETE. */
public record OutgoingMessage(String method, String url, JsonNode message) {
    public static final Set<String> METHODS = Set.of("POST", "PATCH", "DELETE");

    public OutgoingMessage {
        method = method == null ? "POST" : method.toUpperCase(Locale.ROOT);
    }

    public OutgoingMessage(String url, JsonNode message) {
        this("POST", url, message);
    }
}
