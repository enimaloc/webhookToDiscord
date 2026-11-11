package fr.enimaloc.w2d.script;

import fr.enimaloc.w2d.history.IncomingRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScriptRunnerTest {

    static final String URL = "https://discord.com/api/webhooks/123/abc-DEF_1";
    final ScriptRunner runner = new ScriptRunner(Duration.ofMillis(500));

    @AfterAll
    void close() {
        runner.close();
    }

    static ScriptRequest req(String body) {
        return req(body, Map.of(), null);
    }

    static ScriptRequest req(String body, Map<String, List<String>> headers, String query) {
        return ScriptRequest.from(new IncomingRequest(1, "ep", Instant.now(), "POST", headers, query,
                body.getBytes(StandardCharsets.UTF_8), IncomingRequest.RequestStatus.PENDING));
    }

    @Test
    void returnsMessages() {
        var r = runner.run("function handle(q){ console.log('hi'); return [{url:'" + URL + "', message:{content: q.json.a}}]; }",
                req("{\"a\":\"x\"}"));
        assertThat(r.ok()).isTrue();
        assertThat(r.messages()).singleElement().satisfies(m -> {
            assertThat(m.url()).isEqualTo(URL);
            assertThat(m.message().get("content").asText()).isEqualTo("x");
        });
        assertThat(r.logs()).contains("hi");
    }

    @Test
    void nullResultMeansNoMessage() {
        var r = runner.run("function handle(q){ return null; }", req("{}"));
        assertThat(r.ok()).isTrue();
        assertThat(r.messages()).isEmpty();
    }

    @Test
    void nonJsonBodyGivesNullJson() {
        var r = runner.run("function handle(q){ return q.json === null && q.body === 'a=1&b=2' ? [{url:'" + URL
                + "', message:{content:'ok'}}] : []; }", req("a=1&b=2"));
        assertThat(r.ok()).isTrue();
        assertThat(r.messages()).hasSize(1);
    }

    @Test
    void headersAndQueryExposed() {
        var r = runner.run("function handle(q){ return q.headers['x-github-event'][0] === 'push' && q.query.a.length === 2"
                        + " && q.method === 'POST' ? [{url:'" + URL + "', message:{content:'ok'}}] : []; }",
                req("{}", Map.of("x-github-event", List.of("push")), "a=1&a=2"));
        assertThat(r.ok()).isTrue();
        assertThat(r.messages()).hasSize(1);
    }

    @Test
    void queryIsUrlDecoded() {
        var q = req("", Map.of(), "msg=hello%20world&x=a+b&flag").query();
        assertThat(q).containsEntry("msg", List.of("hello world"))
                .containsEntry("x", List.of("a b"))
                .containsEntry("flag", List.of(""));
    }

    @Test
    void invalidUtf8IsReplaced() {
        var r = ScriptRequest.from(new IncomingRequest(1, "ep", Instant.now(), "POST", Map.of(), null,
                new byte[]{'a', (byte) 0xFF, 'b'}, IncomingRequest.RequestStatus.PENDING));
        assertThat(r.body()).isEqualTo("a�b");
        assertThat(r.json()).isNull();
        assertThat(r.query()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "function handle(){ throw new Error('boom'); }",
            "function handle( {",
            "var x = 1;",
            "function handle(){ return {url:'x'}; }",
            "function handle(){ return [{url:'https://evil.example/x', message:{}}]; }",
            "function handle(){ return [{url:'" + URL + "'}]; }"
    })
    void scriptErrorsAreReported(String script) {
        var r = runner.run(script, req("{}"));
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).isNotBlank();
        assertThat(r.messages()).isEmpty();
    }

    @Test
    void errorMessagesAreInformative() {
        assertThat(runner.run("function handle(){ throw new Error('boom'); }", req("{}")).error()).contains("boom");
        assertThat(runner.run("var x = 1;", req("{}")).error()).contains("handle(request) is not defined");
        assertThat(runner.run("function handle(){ return [{url:'" + URL + "', message:{}}, {url:'https://evil.example/x', message:{}}]; }",
                req("{}")).error()).contains("1");
    }

    @Test
    void tooManyMessagesRejected() {
        var r = runner.run("function handle(){ var a=[]; for (var i=0;i<26;i++) a.push({url:'" + URL
                + "', message:{content:'m'+i}}); return a; }", req("{}"));
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("25");
    }

    @Test
    void oversizedResultRejected() {
        var r = runner.run("function handle(){ return [{url:'" + URL + "', message:{content:'x'.repeat(1100000)}}]; }",
                req("{}"));
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("1 MiB");
    }

    @Test
    void infiniteLoopIsCancelled() {
        long t0 = System.nanoTime();
        var r = runner.run("function handle(){ while(true){} }", req("{}"));
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(3));

        // engine must remain usable after a cancelled context
        var after = runner.run("function handle(){ return [{url:'" + URL + "', message:{content:'ok'}}]; }", req("{}"));
        assertThat(after.ok()).isTrue();
        assertThat(after.messages()).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "function handle(){ var S = Java.type('java.lang.System'); S.exit(1); return []; } | Access to host class java.lang.System is not allowed",
            "function handle(){ load('/etc/passwd'); return []; } | Operation is not allowed for: /etc/passwd",
            "function handle(){ load('https://example.com/x.js'); return []; } | Cannot load script",
            "function handle(){ return Polyglot.eval('js', '1'); } | Polyglot is not defined",
            "function handle(){ return Packages.java.lang.System.getenv(); } | is not a function",
            "function handle(){ return java.lang.System.getenv(); } | is not a function"
    })
    void sandboxEscapesAreBlocked(String script, String expectedError) {
        var r = runner.run(script, req("{}"));
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains(expectedError);
    }

    @Test
    void invalidTimeoutRejected() {
        assertThatNullPointerException().isThrownBy(() -> new ScriptRunner(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ScriptRunner(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> new ScriptRunner(Duration.ofMillis(-1)));
    }

    @Test
    void logsAreTruncated() {
        var r = runner.run("function handle(){ for (var i=0;i<2000;i++) console.log('x'.repeat(100)); return []; }", req("{}"));
        assertThat(r.ok()).isTrue();
        assertThat(r.logs().length()).isLessThanOrEqualTo(64 * 1024 + 64);
    }

    @Test
    void methodDefaultsToPost() {
        var r = runner.run("function handle(){ return [{url:'" + URL + "', message:{content:'a'}}]; }", req("{}"));
        assertThat(r.ok()).isTrue();
        assertThat(r.messages().get(0).method()).isEqualTo("POST");
    }

    @Test
    void patchAndDeleteTargetAMessage() {
        var r = runner.run("function handle(){ return [{method:'patch', url:'" + URL + "/messages/42', message:{content:'b'}},"
                + " {method:'DELETE', url:'" + URL + "/messages/42', message:{content:'ignored'}}]; }", req("{}"));
        assertThat(r.ok()).as(r.error()).isTrue();
        assertThat(r.messages().get(0).method()).isEqualTo("PATCH");
        assertThat(r.messages().get(0).message().path("content").asText()).isEqualTo("b");
        assertThat(r.messages().get(1).method()).isEqualTo("DELETE");
        assertThat(r.messages().get(1).message()).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "PATCH|''|end with /messages/<id> for PATCH",
            "DELETE|''|end with /messages/<id> for DELETE",
            "POST|/messages/42|not end with /messages/<id> for POST",
            "PUT|/messages/42|must be POST, PATCH or DELETE"})
    void invalidMethodsRejected(String method, String suffix, String error) {
        var r = runner.run("function handle(){ return [{method:'" + method + "', url:'" + URL + suffix
                + "', message:{content:'x'}}]; }", req("{}"));
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains(error);
    }
}
