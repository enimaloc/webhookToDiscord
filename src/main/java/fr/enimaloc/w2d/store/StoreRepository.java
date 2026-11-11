package fr.enimaloc.w2d.store;

import org.jdbi.v3.core.Jdbi;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-entry key/value memory kept between script runs (e.g. Discord message ids to edit later). */
public final class StoreRepository {
    private final Jdbi jdbi;

    public StoreRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public Map<String, String> all(String endpointId) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbi.useHandle(h -> h.createQuery("SELECT key, value FROM store WHERE endpoint_id = :endpoint ORDER BY key")
                .bind("endpoint", endpointId)
                .map((rs, ctx) -> Map.entry(rs.getString("key"), rs.getString("value")))
                .forEach(e -> out.put(e.getKey(), e.getValue())));
        return out;
    }

    public void put(String endpointId, String key, String value) {
        jdbi.useHandle(h -> h.createUpdate("INSERT INTO store (endpoint_id, key, value) VALUES (:endpoint, :key, :value) "
                        + "ON CONFLICT (endpoint_id, key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()")
                .bind("endpoint", endpointId).bind("key", key).bind("value", value).execute());
    }

    public boolean remove(String endpointId, String key) {
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM store WHERE endpoint_id = :endpoint AND key = :key")
                .bind("endpoint", endpointId).bind("key", key).execute()) > 0;
    }

    public int purgeOlderThan(Instant cutoff) {
        return jdbi.withHandle(h -> h.createUpdate("DELETE FROM store WHERE updated_at < :cutoff")
                .bind("cutoff", Timestamp.from(cutoff)).execute());
    }
}
