package backend.ingest;

import backend.test.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TestPropertySource(properties = {
        "ingest.timeout.enabled=true",
        "ingest.timeout.stale-ms=1000",
        "ingest.timeout.batch-size=100",
        "ingest.timeout.error-message=TEST_TIMEOUT"
})
@Tag("it")
class IngestTimeoutSchedulerIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IngestTimeoutScheduler scheduler;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("TRUNCATE TABLE outbox_events, ingest_jobs, resource_tags, tags, links, documents, resources RESTART IDENTITY CASCADE");
    }

    @Test
    void sweep_marksStaleProcessingJobsAsFailedTimeout_only() {
        long staleId = insertLinkResource("https://example.com/stale", "example.com");
        insertProcessingIngestJob(staleId);
        // stale-ms=1000 => make updated_at older than 1s
        jdbc.update("UPDATE ingest_jobs SET updated_at = NOW() - INTERVAL '2 seconds' WHERE resource_id = ?", staleId);

        long freshId = insertLinkResource("https://example.com/fresh", "example.com");
        insertProcessingIngestJob(freshId);

        scheduler.sweepStaleProcessingJobs();

        Map<String, Object> staleRow = jdbc.queryForMap(
                "SELECT status, stage, error_message FROM ingest_jobs WHERE resource_id = ?",
                staleId
        );
        assertEquals("failed", staleRow.get("status"));
        assertEquals("timeout", staleRow.get("stage"));
        assertEquals("TEST_TIMEOUT", staleRow.get("error_message"));

        Map<String, Object> freshRow = jdbc.queryForMap(
                "SELECT status, stage FROM ingest_jobs WHERE resource_id = ?",
                freshId
        );
        assertEquals("processing", freshRow.get("status"));
        assertEquals("embedding", freshRow.get("stage"));
    }

    @Test
    void sweep_doesNotTouchNonProcessingJobs_evenIfStale() {
        long queuedId = insertLinkResource("https://example.com/queued", "example.com");
        insertIngestJob(queuedId, "queued", "queued-stage");
        jdbc.update("UPDATE ingest_jobs SET updated_at = NOW() - INTERVAL '2 seconds' WHERE resource_id = ?", queuedId);

        long cancelledId = insertLinkResource("https://example.com/cancelled", "example.com");
        insertIngestJob(cancelledId, "cancelled", "cancelled");
        jdbc.update("UPDATE ingest_jobs SET updated_at = NOW() - INTERVAL '2 seconds' WHERE resource_id = ?", cancelledId);

        long failedId = insertLinkResource("https://example.com/failed", "example.com");
        insertIngestJob(failedId, "failed", "failed-stage");
        jdbc.update("UPDATE ingest_jobs SET updated_at = NOW() - INTERVAL '2 seconds' WHERE resource_id = ?", failedId);

        long doneId = insertLinkResource("https://example.com/done", "example.com");
        insertIngestJob(doneId, "done", "done-stage");
        jdbc.update("UPDATE ingest_jobs SET updated_at = NOW() - INTERVAL '2 seconds' WHERE resource_id = ?", doneId);

        scheduler.sweepStaleProcessingJobs();

        assertEquals("queued", jdbc.queryForObject("SELECT status FROM ingest_jobs WHERE resource_id = ?", String.class, queuedId));
        assertEquals("queued-stage", jdbc.queryForObject("SELECT stage FROM ingest_jobs WHERE resource_id = ?", String.class, queuedId));

        assertEquals("cancelled", jdbc.queryForObject("SELECT status FROM ingest_jobs WHERE resource_id = ?", String.class, cancelledId));
        assertEquals("cancelled", jdbc.queryForObject("SELECT stage FROM ingest_jobs WHERE resource_id = ?", String.class, cancelledId));

        assertEquals("failed", jdbc.queryForObject("SELECT status FROM ingest_jobs WHERE resource_id = ?", String.class, failedId));
        assertEquals("failed-stage", jdbc.queryForObject("SELECT stage FROM ingest_jobs WHERE resource_id = ?", String.class, failedId));

        assertEquals("done", jdbc.queryForObject("SELECT status FROM ingest_jobs WHERE resource_id = ?", String.class, doneId));
        assertEquals("done-stage", jdbc.queryForObject("SELECT stage FROM ingest_jobs WHERE resource_id = ?", String.class, doneId));
    }

    private long insertLinkResource(String url, String domain) {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO resources (type, title, memo, status, is_pinned)
                VALUES ('link', 'test link', '', 'todo', false)
                RETURNING id
                """,
                Long.class
        );
        if (id == null) throw new IllegalStateException("failed to insert resources row");

        jdbc.update(
                "INSERT INTO links (resource_id, url, domain) VALUES (?, ?, ?)",
                id, url, domain
        );
        return id;
    }

    private void insertProcessingIngestJob(long resourceId) {
        jdbc.update(
                """
                INSERT INTO ingest_jobs (resource_id, resource_type, title, status, stage)
                VALUES (?, 'link', 'test title', 'processing', 'embedding')
                """,
                resourceId
        );
    }

    private void insertIngestJob(long resourceId, String status, String stage) {
        jdbc.update(
                """
                INSERT INTO ingest_jobs (resource_id, resource_type, title, status, stage)
                VALUES (?, 'link', 'test title', ?, ?)
                """,
                resourceId, status, stage
        );
    }
}
