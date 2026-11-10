package fr.enimaloc.w2d;

import fr.enimaloc.w2d.endpoint.EndpointRepository;
import fr.enimaloc.w2d.history.HistoryRepository;
import fr.enimaloc.w2d.processing.EntryProcessor;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThatCode;

class AppResumeTest {

    @Test
    void resumeSwallowsDatabaseErrors() {
        // nothing listens on port 1: every query fails with a connection error
        Jdbi jdbi = Jdbi.create("jdbc:postgresql://127.0.0.1:1/none?connectTimeout=1");
        EndpointRepository endpoints = new EndpointRepository(jdbi);
        HistoryRepository history = new HistoryRepository(jdbi);
        var executor = Executors.newSingleThreadExecutor();
        try {
            EntryProcessor processor = new EntryProcessor(jdbi, endpoints, history, null, executor);
            assertThatCode(() -> App.resumePendingSafely(endpoints, history, processor)).doesNotThrowAnyException();
        } finally {
            executor.shutdownNow();
        }
    }
}
