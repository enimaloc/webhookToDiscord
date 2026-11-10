package fr.enimaloc.w2d.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
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

    private static final Logger LOG = LoggerFactory.getLogger(AppConfig.class);
    private static final int GENERATED_SECRET_BYTES = 32;

    public static AppConfig fromEnv(Map<String, String> env) {
        String adminPassword = required(env, "ADMIN_PASSWORD");
        String databaseUrl = required(env, "DATABASE_URL");
        int port = (int) number(env, "PORT", 8080, 1, 65535);
        String secret = env.get("SESSION_SECRET");
        byte[] sessionSecret;
        if (secret != null && !secret.isBlank()) {
            sessionSecret = secret.getBytes(StandardCharsets.UTF_8);
        } else {
            // never derived from ADMIN_PASSWORD: a stolen token must not enable offline password cracking
            sessionSecret = new byte[GENERATED_SECRET_BYTES];
            new SecureRandom().nextBytes(sessionSecret);
            LOG.warn("SESSION_SECRET is not set: using a random per-process secret; admin sessions will not be "
                    + "shared across pods and will be lost on restart");
        }
        return new AppConfig(
                databaseUrl,
                env.get("DB_USER"),
                env.get("DB_PASSWORD"),
                adminPassword,
                env.getOrDefault("PUBLIC_BASE_URL", "http://localhost:" + port),
                Duration.ofMillis(number(env, "SCRIPT_TIMEOUT_MS", 5000, 1, Long.MAX_VALUE)),
                (int) number(env, "HISTORY_RETENTION_DAYS", 30, 1, Integer.MAX_VALUE),
                port,
                number(env, "MAX_BODY_BYTES", 1_048_576, 1, Long.MAX_VALUE),
                sessionSecret);
    }

    /** Parses an optional integer variable; blank means unset. */
    private static long number(Map<String, String> env, String key, long fallback, long min, long max) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        long parsed;
        try {
            parsed = Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Environment variable " + key + " must be an integer, got: " + value);
        }
        if (parsed < min || parsed > max) {
            String range = max == Long.MAX_VALUE || max == Integer.MAX_VALUE ? ">= " + min
                    : "between " + min + " and " + max;
            throw new IllegalStateException("Environment variable " + key + " must be " + range + ", got: " + parsed);
        }
        return parsed;
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Missing required environment variable " + key);
        }
        return value;
    }

    /** Never prints secrets (passwords, session secret). */
    @Override
    public String toString() {
        return "AppConfig[databaseUrl=" + databaseUrl + ", dbUser=" + dbUser
                + ", dbPassword=" + (dbPassword == null ? null : "***")
                + ", adminPassword=***, publicBaseUrl=" + publicBaseUrl + ", scriptTimeout=" + scriptTimeout
                + ", retentionDays=" + retentionDays + ", port=" + port + ", maxBodyBytes=" + maxBodyBytes
                + ", sessionSecret=***]";
    }
}
