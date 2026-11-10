package fr.enimaloc.w2d.endpoint;

import fr.enimaloc.w2d.endpoint.AuthConfig.Basic;
import fr.enimaloc.w2d.endpoint.AuthConfig.Bearer;
import fr.enimaloc.w2d.endpoint.AuthConfig.Encoding;
import fr.enimaloc.w2d.endpoint.AuthConfig.Hmac;
import fr.enimaloc.w2d.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointRepositoryTest extends PostgresTest {
    private EndpointRepository repo;

    @BeforeEach
    void setUp() {
        repo = new EndpointRepository(jdbi());
    }

    @Test
    void createGeneratesUnguessableId() {
        Endpoint a = repo.create("a");
        Endpoint b = repo.create("b");
        assertThat(a.id()).hasSize(32).matches("[A-Za-z0-9_-]+");
        assertThat(a.id()).isNotEqualTo(b.id());
        assertThat(a.auth()).isInstanceOf(AuthConfig.None.class);
        assertThat(a.enabled()).isTrue();
        assertThat(repo.list()).extracting(Endpoint::name).containsExactly("a", "b");
    }

    @Test
    void updateRoundTripsAuth() {
        Endpoint e = repo.create("x");
        Hmac hmac = new Hmac("X-Hub-Signature-256", "s", "HmacSHA256", "sha256=", Encoding.HEX);
        repo.update(new Endpoint(e.id(), "renamed", false, "function handle(r){return [];}", hmac,
                e.createdAt(), e.updatedAt()));
        Endpoint found = repo.find(e.id()).orElseThrow();
        assertThat(found.auth()).isEqualTo(hmac);
        assertThat(found.name()).isEqualTo("renamed");
        assertThat(found.enabled()).isFalse();
        assertThat(found.script()).isEqualTo("function handle(r){return [];}");
    }

    @Test
    void deleteRemoves() {
        Endpoint e = repo.create("x");
        assertThat(repo.delete(e.id())).isTrue();
        assertThat(repo.find(e.id())).isEmpty();
        assertThat(repo.delete(e.id())).isFalse();
    }

    @Test
    void redactedAndMerge() {
        assertThat(new Bearer("t").redacted()).isEqualTo(new Bearer(null));
        assertThat(new Bearer(null).mergeSecretsFrom(new Bearer("t"))).isEqualTo(new Bearer("t"));
        assertThat(new Bearer("").mergeSecretsFrom(new Bearer("t"))).isEqualTo(new Bearer("t"));
        assertThat(new Bearer("n").mergeSecretsFrom(new Bearer("t"))).isEqualTo(new Bearer("n"));
        assertThat(new Bearer(null).mergeSecretsFrom(new Basic("u", "p"))).isEqualTo(new Bearer(null));
        assertThat(new Basic("u", "p").redacted()).isEqualTo(new Basic("u", null));
        assertThat(new Hmac("h", null, null, null, Encoding.HEX)
                .mergeSecretsFrom(new Hmac("h", "s", "HmacSHA256", null, Encoding.HEX)))
                .isEqualTo(new Hmac("h", "s", "HmacSHA256", null, Encoding.HEX));
    }
}
