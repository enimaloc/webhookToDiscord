package fr.enimaloc.w2d.history;

import fr.enimaloc.w2d.store.StoreRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class RetentionJob {
    private final HistoryRepository history;
    private final StoreRepository store;
    private final int retentionDays;
    private ScheduledExecutorService scheduler;

    /** {@code store} may be null; when set, keys not written within the retention period are purged too. */
    public RetentionJob(HistoryRepository history, StoreRepository store, int retentionDays) {
        this.history = history;
        this.store = store;
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

    /** Returns the number of purged requests. */
    public int runOnce(Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(retentionDays));
        if (store != null) {
            store.purgeOlderThan(cutoff);
        }
        return history.purgeOlderThan(cutoff);
    }
}
