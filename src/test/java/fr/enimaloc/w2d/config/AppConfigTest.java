package fr.enimaloc.w2d.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppConfigTest {

    @Test
    void defaultsApplied() {
        var c = AppConfig.fromEnv(Map.of("ADMIN_PASSWORD", "pw", "DATABASE_URL", "jdbc:postgresql://x/db"));
        assertThat(c.port()).isEqualTo(8080);
        assertThat(c.scriptTimeout()).isEqualTo(Duration.ofMillis(5000));
        assertThat(c.retentionDays()).isEqualTo(30);
        assertThat(c.maxBodyBytes()).isEqualTo(1_048_576);
        assertThat(c.publicBaseUrl()).isEqualTo("http://localhost:8080");
        assertThat(c.sessionSecret()).hasSize(32);
    }

    @Test
    void missingAdminPasswordFails() {
        assertThatThrownBy(() -> AppConfig.fromEnv(Map.of("DATABASE_URL", "jdbc:postgresql://x/db")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ADMIN_PASSWORD");
    }

    @Test
    void missingDatabaseUrlFails() {
        assertThatThrownBy(() -> AppConfig.fromEnv(Map.of("ADMIN_PASSWORD", "pw")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DATABASE_URL");
    }

    @Test
    void overridesAndExplicitSessionSecret() {
        var c = AppConfig.fromEnv(Map.of(
                "ADMIN_PASSWORD", "pw", "DATABASE_URL", "jdbc:postgresql://x/db",
                "PORT", "9090", "SESSION_SECRET", "abc", "SCRIPT_TIMEOUT_MS", "100"));
        assertThat(c.port()).isEqualTo(9090);
        assertThat(c.publicBaseUrl()).isEqualTo("http://localhost:9090");
        assertThat(c.sessionSecret()).isEqualTo("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(c.scriptTimeout()).isEqualTo(Duration.ofMillis(100));
    }
}
