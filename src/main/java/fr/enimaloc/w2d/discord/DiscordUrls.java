package fr.enimaloc.w2d.discord;

import java.util.regex.Pattern;

public final class DiscordUrls {
    private static final Pattern WEBHOOK = Pattern.compile(
            "^https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api(?:/v\\d+)?/webhooks/\\d+/[A-Za-z0-9_-]+(?:\\?.*)?$");

    private DiscordUrls() {
    }

    public static boolean isValid(String url) {
        return url != null && WEBHOOK.matcher(url).matches();
    }
}
