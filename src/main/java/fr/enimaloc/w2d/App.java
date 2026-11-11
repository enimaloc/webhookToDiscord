package fr.enimaloc.w2d;

import fr.enimaloc.w2d.config.AppConfig;
import fr.enimaloc.w2d.db.Database;
import fr.enimaloc.w2d.discord.DiscordSender;
import fr.enimaloc.w2d.endpoint.Endpoint;
import fr.enimaloc.w2d.endpoint.EndpointRepository;
import fr.enimaloc.w2d.history.HistoryRepository;
import fr.enimaloc.w2d.history.RetentionJob;
import fr.enimaloc.w2d.processing.EntryProcessor;
import fr.enimaloc.w2d.processing.Pipeline;
import fr.enimaloc.w2d.script.ScriptRunner;
import fr.enimaloc.w2d.store.StoreRepository;
import fr.enimaloc.w2d.web.AdminAuth;
import fr.enimaloc.w2d.web.ApiController;
import fr.enimaloc.w2d.web.SessionTokens;
import fr.enimaloc.w2d.web.WebhookController;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class App {
    private static final Logger LOG = LoggerFactory.getLogger(App.class);
    private static final long RESUME_INTERVAL_SECONDS = 60;

    private App() {
    }

    /** Health-only app (no database). */
    public static Javalin create(AppConfig config) {
        return Javalin.create(cfg -> {
            cfg.http.maxRequestSize = config.maxBodyBytes();
            cfg.routes.get("/health", ctx -> ctx.result("ok"));
        });
    }

    /** Fully wired app; the caller owns the lifecycle of the given resources. */
    public static Javalin create(AppConfig config, EndpointRepository endpoints, HistoryRepository history,
                                 EntryProcessor processor, Pipeline pipeline) {
        WebhookController webhook = new WebhookController(endpoints, history, processor::trigger,
                config.maxBodyBytes());
        Clock clock = Clock.systemUTC();
        AdminAuth auth = new AdminAuth(config.adminPassword(), SessionTokens.forAdmin(config.sessionSecret(), config.adminPassword(), clock), clock);
        ApiController api = new ApiController(endpoints, history, pipeline, processor::trigger,
                config.publicBaseUrl());
        return Javalin.create(cfg -> {
            cfg.http.maxRequestSize = config.maxBodyBytes();
            cfg.routes.get("/health", ctx -> ctx.result("ok"));
            webhook.register(cfg.routes);
            // public: /health, /webhook/*; guarded: /api/* except login and session
            auth.register(cfg.routes);
            api.register(cfg.routes);
            cfg.staticFiles.add("/public", Location.CLASSPATH);
        });
    }

    /** Re-triggers entries that still have never-started pending requests (e.g. after a restart). */
    static void resumePending(EndpointRepository endpoints, HistoryRepository history, EntryProcessor processor) {
        for (Endpoint e : endpoints.list()) {
            if (history.hasPending(e.id())) {
                processor.trigger(e.id());
            }
        }
    }

    /** Never throws: a database error must not kill the caller (main thread or scheduler). */
    static void resumePendingSafely(EndpointRepository endpoints, HistoryRepository history,
                                    EntryProcessor processor) {
        try {
            resumePending(endpoints, history, processor);
        } catch (RuntimeException e) {
            LOG.error("Resuming pending requests failed", e);
        }
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv(System.getenv());
        Database db = new Database(config);
        EndpointRepository endpoints = new EndpointRepository(db.jdbi());
        HistoryRepository history = new HistoryRepository(db.jdbi());
        ScriptRunner runner = new ScriptRunner(config.scriptTimeout());
        StoreRepository store = new StoreRepository(db.jdbi());
        Pipeline pipeline = new Pipeline(endpoints, history, runner, new DiscordSender(), store);
        // processor threads must stay well below the DB pool size (Hikari default 10)
        AtomicInteger threadNo = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "entry-processor-" + threadNo.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        EntryProcessor processor = new EntryProcessor(db.jdbi(), endpoints, history, pipeline, executor);
        RetentionJob retention = new RetentionJob(history, store, config.retentionDays());
        retention.start();

        // picks up requests whose trigger was lost (e.g. scheduling failure, pod killed before draining)
        ScheduledExecutorService resumer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pending-resume");
            t.setDaemon(true);
            return t;
        });

        Javalin app = create(config, endpoints, history, processor, pipeline);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            retention.stop();
            resumer.shutdownNow();
            executor.shutdown();
            try {
                long graceMs = config.scriptTimeout().toMillis() + 10_000;
                if (!executor.awaitTermination(graceMs, TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            runner.close();
            db.close();
        }));
        app.start(config.port());
        resumePendingSafely(endpoints, history, processor);
        resumer.scheduleWithFixedDelay(() -> resumePendingSafely(endpoints, history, processor),
                RESUME_INTERVAL_SECONDS, RESUME_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }
}
