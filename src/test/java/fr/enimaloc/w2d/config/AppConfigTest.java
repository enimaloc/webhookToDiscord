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
    void generatedSessionSecretIsRandomAndNotDerivedFromPassword() {
        Map<String, String> env = Map.of("ADMIN_PASSWORD", "pw", "DATABASE_URL", "jdbc:postgresql://x/db");
        var a = AppConfig.fromEnv(env);
        var b = AppConfig.fromEnv(env);
        assertThat(a.sessionSecret()).hasSize(32).isNotEqualTo(b.sessionSecret());
        var blank = AppConfig.fromEnv(Map.of("ADMIN_PASSWORD", "pw", "DATABASE_URL", "jdbc:postgresql://x/db",
                "SESSION_SECRET", "   "));
        assertThat(blank.sessionSecret()).hasSize(32).isNotEqualTo(a.sessionSecret());
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

    private static Map<String, String> env(String key, String value) {
        return Map.of("ADMIN_PASSWORD", "pw", "DATABASE_URL", "jdbc:postgresql://x/db", key, value);
    }

    @Test
    void outOfRangeNumbersFailNamingTheVariable() {
        String[][] cases = {
                {"HISTORY_RETENTION_DAYS", "0"}, {"HISTORY_RETENTION_DAYS", "-3"},
                {"SCRIPT_TIMEOUT_MS", "0"}, {"MAX_BODY_BYTES", "0"},
                {"PORT", "0"}, {"PORT", "65536"},
                {"HISTORY_RETENTION_DAYS", "2147483648"}};
        for (String[] c : cases) {
            assertThatThrownBy(() -> AppConfig.fromEnv(env(c[0], c[1])))
                    .as(c[0] + "=" + c[1])
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(c[0]);
        }
    }

    @Test
    void nonNumericFailsNamingTheVariable() {
        for (String key : new String[]{"PORT", "SCRIPT_TIMEOUT_MS", "HISTORY_RETENTION_DAYS", "MAX_BODY_BYTES"}) {
            assertThatThrownBy(() -> AppConfig.fromEnv(env(key, "abc")))
                    .as(key)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(key);
        }
    }

    @Test
    void blankNumbersUseDefaults() {
        var c = AppConfig.fromEnv(Map.of("ADMIN_PASSWORD", "pw", "DATABASE_URL", "jdbc:postgresql://x/db",
                "PORT", "", "SCRIPT_TIMEOUT_MS", " ", "HISTORY_RETENTION_DAYS", "", "MAX_BODY_BYTES", "  "));
        assertThat(c.port()).isEqualTo(8080);
        assertThat(c.scriptTimeout()).isEqualTo(Duration.ofMillis(5000));
        assertThat(c.retentionDays()).isEqualTo(30);
        assertThat(c.maxBodyBytes()).isEqualTo(1_048_576);
    }

    @Test
    void boundaryValuesAccepted() {
        assertThat(AppConfig.fromEnv(env("PORT", "65535")).port()).isEqualTo(65535);
        assertThat(AppConfig.fromEnv(env("PORT", "1")).port()).isEqualTo(1);
        assertThat(AppConfig.fromEnv(env("HISTORY_RETENTION_DAYS", "1")).retentionDays()).isEqualTo(1);
    }

    @Test
    void toStringHidesSecrets() {
        var c = AppConfig.fromEnv(Map.of("ADMIN_PASSWORD", "admin-secret-pw", "DATABASE_URL", "jdbc:postgresql://x/db",
                "DB_USER", "dbuser", "DB_PASSWORD", "db-secret-pw", "SESSION_SECRET", "session-secret-value"));
        String text = c.toString();
        assertThat(text).doesNotContain("admin-secret-pw", "db-secret-pw", "session-secret-value")
                .contains("dbuser", "jdbc:postgresql://x/db");
    }
}
