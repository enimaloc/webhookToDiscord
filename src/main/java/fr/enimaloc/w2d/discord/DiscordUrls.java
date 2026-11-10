package fr.enimaloc.w2d.discord;

import java.util.regex.Pattern;

public final class DiscordUrls {
    private static final Pattern WEBHOOK = Pattern.compile(
            "^https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api(?:/v\\d+)?/webhooks/\\d+/[A-Za-z0-9_-]+(?:\\?.*)?$");

    private static final Pattern MASK = Pattern.compile("(/webhooks/\\d+/)[^/?#]+");

    private DiscordUrls() {
    }

    public static boolean isValid(String url) {
        return url != null && WEBHOOK.matcher(url).matches();
    }

    /** Hides the token: {@code .../webhooks/<id>/***}, query string kept. */
    public static String mask(String url) {
        if (url == null) {
            return null;
        }
        return MASK.matcher(url).replaceFirst("$1***");
    }
}
