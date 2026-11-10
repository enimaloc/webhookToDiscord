package fr.enimaloc.w2d.discord;

/** Outcome of one outgoing message; {@code url} is masked, {@code status} is 0 on network error. */
public record SendResult(String url, int status, int attempts, String response, boolean ok) {
}
