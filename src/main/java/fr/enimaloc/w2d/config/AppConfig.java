package fr.enimaloc.w2d.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;

public record AppConfig(
        String databaseUrl,
        String dbUser,
        String dbPassword,
        String adminPassword,
        String publicBaseUrl,
        Duration scriptTimeout,
        int retentionDays,
        int port,
        long maxBodyBytes,
        byte[] sessionSecret) {

    public static AppConfig fromEnv(Map<String, String> env) {
        String adminPassword = required(env, "ADMIN_PASSWORD");
        String databaseUrl = required(env, "DATABASE_URL");
        int port = Integer.parseInt(env.getOrDefault("PORT", "8080"));
        String secret = env.get("SESSION_SECRET");
        byte[] sessionSecret = secret != null && !secret.isEmpty()
                ? secret.getBytes(StandardCharsets.UTF_8)
                : sha256("w2d-session:" + adminPassword);
        return new AppConfig(
                databaseUrl,
                env.get("DB_USER"),
                env.get("DB_PASSWORD"),
                adminPassword,
                env.getOrDefault("PUBLIC_BASE_URL", "http://localhost:" + port),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("SCRIPT_TIMEOUT_MS", "5000"))),
                Integer.parseInt(env.getOrDefault("HISTORY_RETENTION_DAYS", "30")),
                port,
                Long.parseLong(env.getOrDefault("MAX_BODY_BYTES", "1048576")),
                sessionSecret);
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Missing required environment variable " + key);
        }
        return value;
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
