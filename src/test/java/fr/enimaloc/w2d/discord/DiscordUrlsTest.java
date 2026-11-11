package fr.enimaloc.w2d.discord;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DiscordUrlsTest {
    @Test
    void acceptsValidUrls() {
        assertThat(DiscordUrls.isValid("https://discord.com/api/webhooks/1/a")).isTrue();
        assertThat(DiscordUrls.isValid("https://ptb.discord.com/api/v10/webhooks/1/a?thread_id=2")).isTrue();
        assertThat(DiscordUrls.isValid("https://discordapp.com/api/webhooks/1/a")).isTrue();
    }

    @Test
    void rejectsInvalidUrls() {
        assertThat(DiscordUrls.isValid("http://discord.com/api/webhooks/1/a")).isFalse();
        assertThat(DiscordUrls.isValid("https://discord.com.evil.io/api/webhooks/1/a")).isFalse();
        assertThat(DiscordUrls.isValid("https://evil.io/?https://discord.com/api/webhooks/1/a")).isFalse();
        assertThat(DiscordUrls.isValid("https://discord.com/api/webhooks/abc/a")).isFalse();
    }

    @Test
    void maskUrlHidesToken() {
        assertThat(DiscordUrls.mask("https://discord.com/api/webhooks/1/secretTok?wait=true"))
                .isEqualTo("https://discord.com/api/webhooks/1/***?wait=true");
    }

    @Test
    void messageUrls() {
        String url = "https://discord.com/api/webhooks/1/tok/messages/42?thread_id=3";
        assertThat(DiscordUrls.isValid(url)).isTrue();
        assertThat(DiscordUrls.targetsMessage(url)).isTrue();
        assertThat(DiscordUrls.targetsMessage("https://discord.com/api/webhooks/1/tok?wait=true")).isFalse();
        assertThat(DiscordUrls.isValid("https://discord.com/api/webhooks/1/tok/messages/abc")).isFalse();
        assertThat(DiscordUrls.mask(url)).isEqualTo("https://discord.com/api/webhooks/1/***/messages/42?thread_id=3");
    }
}
