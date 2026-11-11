package fr.enimaloc.w2d.store;

import fr.enimaloc.w2d.endpoint.EndpointRepository;
import fr.enimaloc.w2d.support.PostgresTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StoreRepositoryTest extends PostgresTest {

    @Test
    void putOverwritesAndRemoveDeletes() {
        EndpointRepository endpoints = new EndpointRepository(db.jdbi());
        String a = endpoints.create("a").id();
        String b = endpoints.create("b").id();
        StoreRepository store = new StoreRepository(db.jdbi());

        store.put(a, "k", "1");
        store.put(a, "k", "2");
        store.put(b, "k", "3");
        assertThat(store.all(a)).containsExactly(Map.entry("k", "2"));

        assertThat(store.remove(a, "k")).isTrue();
        assertThat(store.remove(a, "k")).isFalse();
        assertThat(store.all(a)).isEmpty();
        assertThat(store.all(b)).containsExactly(Map.entry("k", "3"));
    }

    @Test
    void purgeDropsStaleKeys() {
        String a = new EndpointRepository(db.jdbi()).create("a").id();
        StoreRepository store = new StoreRepository(db.jdbi());
        store.put(a, "old", "1");
        db.jdbi().useHandle(h -> h.execute("UPDATE store SET updated_at = now() - interval '31 days'"));
        store.put(a, "fresh", "2");

        assertThat(store.purgeOlderThan(Instant.now().minusSeconds(30L * 24 * 3600))).isEqualTo(1);
        assertThat(store.all(a)).containsExactly(Map.entry("fresh", "2"));
    }
}
