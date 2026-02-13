package backend.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IngestTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestTimeoutScheduler.class);

    private final IngestStatusService ingestStatusService;
    private final boolean enabled;
    private final long staleMs;
    private final int batchSize;
    private final String timeoutErrorMessage;
    private boolean invalidConfigLogged = false;

    public IngestTimeoutScheduler(
            IngestStatusService ingestStatusService,
            @Value("${ingest.timeout.enabled:true}") boolean enabled,
            @Value("${ingest.timeout.stale-ms:1800000}") long staleMs,
            @Value("${ingest.timeout.batch-size:100}") int batchSize,
            @Value("${ingest.timeout.error-message:처리 시간 초과}") String timeoutErrorMessage
    ) {
        this.ingestStatusService = ingestStatusService;
        this.enabled = enabled;
        this.staleMs = staleMs;
        this.batchSize = batchSize;
        this.timeoutErrorMessage = timeoutErrorMessage;
    }

    @Scheduled(fixedDelayString = "${ingest.timeout.sweep-interval-ms:60000}")
    public void sweepStaleProcessingJobs() {
        if (!enabled) {
            return;
        }
        if (staleMs <= 0) {
            if (!invalidConfigLogged) {
                invalidConfigLogged = true;
                log.warn("Ingest timeout sweep disabled because ingest.timeout.stale-ms <= 0");
            }
            return;
        }

        List<Long> timedOutIds = ingestStatusService.markStaleProcessingAsFailed(
                staleMs,
                batchSize,
                timeoutErrorMessage
        );
        if (!timedOutIds.isEmpty()) {
            log.warn("Timed out {} ingest job(s): {}", timedOutIds.size(), timedOutIds);
        }
    }
}
