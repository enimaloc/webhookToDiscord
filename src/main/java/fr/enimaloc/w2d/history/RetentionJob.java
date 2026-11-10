package fr.enimaloc.w2d.history;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class RetentionJob {
    private final HistoryRepository history;
    private final int retentionDays;
    private ScheduledExecutorService scheduler;

    public RetentionJob(HistoryRepository history, int retentionDays) {
        this.history = history;
        this.retentionDays = retentionDays;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "history-retention");
            t.setDaemon(true);
            return t;
        });
        // Exceptions would cancel a scheduleAtFixedRate task, so swallow them per run.
        scheduler.scheduleAtFixedRate(() -> {
            try {
                runOnce(Instant.now());
            } catch (RuntimeException ex) {
                System.err.println("History retention failed: " + ex);
            }
        }, 0, 1, TimeUnit.HOURS);
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public int runOnce(Instant now) {
        return history.purgeOlderThan(now.minus(Duration.ofDays(retentionDays)));
    }
}
