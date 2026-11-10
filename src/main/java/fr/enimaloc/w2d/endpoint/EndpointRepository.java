package fr.enimaloc.w2d.endpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import fr.enimaloc.w2d.json.Json;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.StatementContext;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

public final class EndpointRepository {
    static final String DEFAULT_SCRIPT = """
            // request: { method, headers, query, body, json }
            // return [{ url: "https://discord.com/api/webhooks/...", message: { content: "..." } }]
            function handle(request) {
              return [];
            }
            """;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String COLUMNS = "id, name, enabled, script, auth::text AS auth, created_at, updated_at";

    private final Jdbi jdbi;

    public EndpointRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public Endpoint create(String name) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return jdbi.withHandle(h -> h.createQuery(
                        "INSERT INTO endpoints (id, name, enabled, script, auth) "
                                + "VALUES (:id, :name, true, :script, CAST(:auth AS jsonb)) RETURNING " + COLUMNS)
                .bind("id", id).bind("name", name).bind("script", DEFAULT_SCRIPT)
                .bind("auth", writeAuth(new AuthConfig.None()))
                .map(EndpointRepository::map).one());
    }

    public Optional<Endpoint> find(String id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT " + COLUMNS + " FROM endpoints WHERE id = :id")
                .bind("id", id).map(EndpointRepository::map).findOne());
    }

    public List<Endpoint> list() {
        return jdbi.withHandle(h -> h.createQuery("SELECT " + COLUMNS + " FROM endpoints ORDER BY created_at, id")
                .map(EndpointRepository::map).list());
    }

    public Endpoint update(Endpoint e) {
        return jdbi.withHandle(h -> h.createQuery(
                        "UPDATE endpoints SET name = :name, enabled = :enabled, script = :script, "
                                + "auth = CAST(:auth AS jsonb), updated_at = now() WHERE id = :id RETURNING " + COLUMNS)
                .bind("id", e.id()).bind("name", e.name()).bind("enabled", e.enabled())
                .bind("script", e.script()).bind("auth", writeAuth(e.auth()))
                .map(EndpointRepository::map).findOne()
                .orElseThrow(() -> new IllegalArgumentException("Unknown endpoint " + e.id())));
    }

    public boolean delete(String id) {
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM endpoints WHERE id = :id")
                .bind("id", id).execute()) > 0;
    }

    private static String writeAuth(AuthConfig auth) {
        try {
            return Json.MAPPER.writeValueAsString(auth);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Endpoint map(ResultSet rs, StatementContext ctx) throws SQLException {
        try {
            return new Endpoint(rs.getString("id"), rs.getString("name"), rs.getBoolean("enabled"),
                    rs.getString("script"), Json.MAPPER.readValue(rs.getString("auth"), AuthConfig.class),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
        } catch (JsonProcessingException ex) {
            throw new SQLException("Invalid auth json", ex);
        }
    }
}
