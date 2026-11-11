package fr.enimaloc.w2d.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import fr.enimaloc.w2d.discord.DiscordUrls;
import fr.enimaloc.w2d.json.Json;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs untrusted user scripts in a locked-down GraalJS context. Data only crosses the boundary as JSON strings.
 */
public final class ScriptRunner implements AutoCloseable {

    public static final int MAX_MESSAGES = 25;
    public static final int MAX_RESULT_BYTES = 1024 * 1024;
    public static final int MAX_LOG_BYTES = 64 * 1024;

    private static final String HARNESS = """
            (function (req) {
              if (typeof handle !== 'function') throw new Error('handle(request) is not defined');
              const r = handle(JSON.parse(req));
              const s = r == null ? '[]' : JSON.stringify(r);
              if (typeof s === 'string' && s.length > %d) throw new Error('Script result exceeds the 1 MiB limit');
              return s;
            })
            """.formatted(MAX_RESULT_BYTES);
    private static final Source HARNESS_SOURCE = Source.newBuilder("js", HARNESS, "harness.js").buildLiteral();

    private final Duration timeout;
    private final Engine engine;
    private final ScheduledExecutorService watchdog;

    public ScriptRunner(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        this.timeout = timeout;
        this.engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "script-watchdog");
            t.setDaemon(true);
            return t;
        });
        // cancelled kill tasks must not stay queued, holding references to closed contexts
        executor.setRemoveOnCancelPolicy(true);
        this.watchdog = executor;
    }

    public ScriptResult run(String script, ScriptRequest request) {
        String input;
        try {
            input = Json.MAPPER.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            return ScriptResult.failure("", "Could not serialize request: " + e.getOriginalMessage());
        }

        BoundedOutput logs = new BoundedOutput(MAX_LOG_BYTES);
        AtomicBoolean timedOut = new AtomicBoolean(false);
        String output;
        Context context = Context.newBuilder("js")
                .engine(engine)
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(c -> false)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowCreateProcess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .out(logs)
                .err(logs)
                .build();
        ScheduledFuture<?> kill = watchdog.schedule(() -> {
            timedOut.set(true);
            context.close(true);
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            context.eval(Source.newBuilder("js", script == null ? "" : script, "script.js").buildLiteral());
            Value result = context.eval(HARNESS_SOURCE).execute(input);
            if (!result.isString()) {
                return ScriptResult.failure(logs.text(), "handle(request) must return an array of messages");
            }
            output = result.asString();
        } catch (PolyglotException e) {
            if (timedOut.get() || e.isCancelled()) {
                return ScriptResult.failure(logs.text(), timeoutMessage());
            }
            return ScriptResult.failure(logs.text(), describe(e));
        } catch (IllegalStateException e) {
            // context was closed by the watchdog between two calls
            if (timedOut.get()) {
                return ScriptResult.failure(logs.text(), timeoutMessage());
            }
            return ScriptResult.failure(logs.text(), "Script execution failed: " + e.getMessage());
        } finally {
            kill.cancel(false);
            try {
                context.close(true);
            } catch (RuntimeException ignored) {
                // already closed or cancelled
            }
        }
        if (timedOut.get()) {
            return ScriptResult.failure(logs.text(), timeoutMessage());
        }
        return validate(output, logs.text());
    }

    private String timeoutMessage() {
        return "Script timed out after " + timeout.toMillis() + " ms";
    }

    private static String describe(PolyglotException e) {
        if (e.isInternalError()) {
            return "Internal script engine error";
        }
        String msg = e.getMessage();
        if (e.isSyntaxError()) {
            return "Syntax error: " + msg;
        }
        return msg == null ? "Script failed" : msg;
    }

    static ScriptResult validate(String output, String logs) {
        if (output.length() > MAX_RESULT_BYTES
                || output.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            return ScriptResult.failure(logs, "Script result exceeds " + MAX_RESULT_BYTES + " bytes");
        }
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(output);
        } catch (JsonProcessingException e) {
            return ScriptResult.failure(logs, "Script result is not valid JSON");
        }
        if (root == null || !root.isArray()) {
            return ScriptResult.failure(logs, "handle(request) must return an array of messages");
        }
        if (root.size() > MAX_MESSAGES) {
            return ScriptResult.failure(logs,
                    "Script returned " + root.size() + " messages (max " + MAX_MESSAGES + ")");
        }
        List<OutgoingMessage> messages = new ArrayList<>(root.size());
        for (int i = 0; i < root.size(); i++) {
            JsonNode item = root.get(i);
            if (!item.isObject()) {
                return ScriptResult.failure(logs, "messages[" + i + "] must be an object");
            }
            JsonNode url = item.get("url");
            if (url == null || !url.isTextual() || !DiscordUrls.isValid(url.asText())) {
                return ScriptResult.failure(logs, "messages[" + i + "].url is not a valid Discord webhook URL");
            }
            JsonNode methodNode = item.get("method");
            if (methodNode != null && !methodNode.isNull() && !methodNode.isTextual()) {
                return ScriptResult.failure(logs, "messages[" + i + "].method must be a string");
            }
            String method = methodNode == null || methodNode.isNull()
                    ? "POST" : methodNode.asText().toUpperCase(Locale.ROOT);
            if (!OutgoingMessage.METHODS.contains(method)) {
                return ScriptResult.failure(logs, "messages[" + i + "].method must be POST, PATCH or DELETE");
            }
            boolean targetsMessage = DiscordUrls.targetsMessage(url.asText());
            if (method.equals("POST") == targetsMessage) {
                return ScriptResult.failure(logs, "messages[" + i + "].url must " + (targetsMessage ? "not " : "")
                        + "end with /messages/<id> for " + method);
            }
            JsonNode message = item.get("message");
            if (method.equals("DELETE")) {
                message = null;
            } else if (message == null || !message.isObject()) {
                return ScriptResult.failure(logs, "messages[" + i + "].message must be an object");
            }
            messages.add(new OutgoingMessage(method, url.asText(), message));
        }
        return ScriptResult.success(messages, logs);
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
        engine.close(true);
    }

    /** Captures script output, silently dropping anything beyond {@code limit} bytes. */
    private static final class BoundedOutput extends OutputStream {
        private final byte[] buf;
        private int size;
        private boolean truncated;

        BoundedOutput(int limit) {
            this.buf = new byte[limit];
        }

        @Override
        public synchronized void write(int b) {
            if (size < buf.length) {
                buf[size++] = (byte) b;
            } else {
                truncated = true;
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int n = Math.min(len, buf.length - size);
            System.arraycopy(b, off, buf, size, n);
            size += n;
            if (n < len) {
                truncated = true;
            }
        }

        synchronized String text() {
            String s = new String(buf, 0, size, StandardCharsets.UTF_8);
            return truncated ? s + "\n[logs truncated]" : s;
        }
    }
}
