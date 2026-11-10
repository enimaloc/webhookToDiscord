package fr.enimaloc.w2d;

import fr.enimaloc.w2d.config.AppConfig;
import io.javalin.Javalin;

public final class App {

    private App() {
    }

    public static Javalin create(AppConfig config) {
        Javalin app = Javalin.create(cfg -> {
            cfg.routes.get("/health", ctx -> ctx.result("ok"));
        });
        return app;
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv(System.getenv());
        create(config).start(config.port());
    }
}
