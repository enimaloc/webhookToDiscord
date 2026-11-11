package fr.enimaloc.w2d.discord;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Outcome of one outgoing message; {@code url} is masked, {@code status} is 0 on network error. {@code message} is
 * the message object Discord returned (POST with {@code ?wait=true}, PATCH), null otherwise.
 */
public record SendResult(String method, String url, int status, int attempts, String response, JsonNode message,
                         boolean ok) {
    public SendResult(String url, int status, int attempts, String response, boolean ok) {
        this("POST", url, status, attempts, response, null, ok);
    }
}
