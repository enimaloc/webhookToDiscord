package fr.enimaloc.w2d.endpoint;

import java.time.Instant;

public record Endpoint(String id, String name, boolean enabled, String script, AuthConfig auth,
                       Instant createdAt, Instant updatedAt) {
}
