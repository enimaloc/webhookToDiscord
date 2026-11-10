package fr.enimaloc.w2d;

import fr.enimaloc.w2d.config.AppConfig;
import fr.enimaloc.w2d.db.Database;
import fr.enimaloc.w2d.discord.DiscordSender;
import fr.enimaloc.w2d.endpoint.EndpointRepository;
import fr.enimaloc.w2d.history.HistoryRepository;
import fr.enimaloc.w2d.history.RetentionJob;
import fr.enimaloc.w2d.processing.EntryProcessor;
import fr.enimaloc.w2d.processing.Pipeline;
import fr.enimaloc.w2d.script.ScriptRunner;
import fr.enimaloc.w2d.web.AdminAuth;
import fr.enimaloc.w2d.web.ApiController;
import fr.enimaloc.w2d.web.SessionTokens;
import fr.enimaloc.w2d.web.WebhookController;
import io.javalin.Javalin;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class App {

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
    public static Javalin create(AppConfig config, Database db, EntryProcessor processor, Pipeline pipeline) {
        EndpointRepository endpoints = new EndpointRepository(db.jdbi());
        HistoryRepository history = new HistoryRepository(db.jdbi());
        WebhookController webhook = new WebhookController(endpoints, history, processor::trigger,
                config.maxBodyBytes());
        Clock clock = Clock.systemUTC();
        AdminAuth auth = new AdminAuth(config.adminPassword(), new SessionTokens(config.sessionSecret(), clock), clock);
        ApiController api = new ApiController(endpoints, history, pipeline, config.publicBaseUrl());
        return Javalin.create(cfg -> {
            cfg.http.maxRequestSize = config.maxBodyBytes();
            cfg.routes.get("/health", ctx -> ctx.result("ok"));
            webhook.register(cfg.routes);
            // public: /health, /webhook/*; guarded: /api/* except login and session
            auth.register(cfg.routes);
            api.register(cfg.routes);
        });
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv(System.getenv());
        Database db = new Database(config);
        EndpointRepository endpoints = new EndpointRepository(db.jdbi());
        HistoryRepository history = new HistoryRepository(db.jdbi());
        ScriptRunner runner = new ScriptRunner(config.scriptTimeout());
        Pipeline pipeline = new Pipeline(endpoints, history, runner, new DiscordSender());
        // processor threads must stay well below the DB pool size (Hikari default 10)
        AtomicInteger threadNo = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "entry-processor-" + threadNo.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        EntryProcessor processor = new EntryProcessor(db.jdbi(), endpoints, history, pipeline, executor);
        RetentionJob retention = new RetentionJob(history, config.retentionDays());
        retention.start();

        Javalin app = create(config, db, processor, pipeline);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            retention.stop();
            executor.shutdown();
            runner.close();
            db.close();
        }));
        app.start(config.port());
    }
}
