package fr.enimaloc.w2d.support;

import fr.enimaloc.w2d.config.AppConfig;
import fr.enimaloc.w2d.db.Database;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

/** Base class: one shared Postgres container, a fresh Database per test class, tables truncated per test. */
public abstract class PostgresTest {
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    protected static Database db;

    @BeforeAll
    static void openDatabase() {
        db = new Database(config());
    }

    @AfterAll
    static void closeDatabase() {
        db.close();
    }

    @BeforeEach
    void truncate() {
        db.jdbi().useHandle(h -> h.execute("TRUNCATE endpoints CASCADE"));
    }

    protected static AppConfig config() {
        return AppConfig.fromEnv(Map.of(
                "DATABASE_URL", POSTGRES.getJdbcUrl(),
                "DB_USER", POSTGRES.getUsername(),
                "DB_PASSWORD", POSTGRES.getPassword(),
                "ADMIN_PASSWORD", "admin"));
    }

    protected static Jdbi jdbi() {
        return db.jdbi();
    }
}
