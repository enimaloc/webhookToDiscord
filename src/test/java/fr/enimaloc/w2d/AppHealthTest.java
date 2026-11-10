package fr.enimaloc.w2d;

import fr.enimaloc.w2d.config.AppConfig;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppHealthTest {

    @Test
    void healthIsOk() {
        var config = AppConfig.fromEnv(Map.of("ADMIN_PASSWORD", "pw", "DATABASE_URL", "jdbc:postgresql://x/db"));
        JavalinTest.test(App.create(config), (server, client) -> {
            var response = client.get("/health");
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("ok");
        });
    }
}
