package fr.enimaloc.w2d.discord;

import java.util.regex.Pattern;

public final class DiscordUrls {
    private static final Pattern WEBHOOK = Pattern.compile(
            "^https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api(?:/v\\d+)?/webhooks/\\d+/[A-Za-z0-9_-]+(?:/messages/\\d+)?(?:\\?.*)?$");
    private static final Pattern MESSAGE_PATH = Pattern.compile("/webhooks/\\d+/[^/?#]+/messages/\\d+(?:\\?|$)");

    private static final Pattern MASK = Pattern.compile("(/webhooks/\\d+/)[^/?#]+");

    private DiscordUrls() {
    }

    public static boolean isValid(String url) {
        return url != null && WEBHOOK.matcher(url).matches();
    }

    /** True when the URL targets an existing message ({@code .../messages/<id>}), as PATCH and DELETE require. */
    public static boolean targetsMessage(String url) {
        return url != null && MESSAGE_PATH.matcher(url).find();
    }

    /** Hides the token: {@code .../webhooks/<id>/***}, query string kept. */
    public static String mask(String url) {
        if (url == null) {
            return null;
        }
        return MASK.matcher(url).replaceFirst("$1***");
    }
}
