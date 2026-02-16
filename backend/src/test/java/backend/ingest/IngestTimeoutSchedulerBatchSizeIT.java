package backend.ingest;

import backend.test.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TestPropertySource(properties = {
        "ingest.timeout.enabled=true",
        "ingest.timeout.stale-ms=1000",
        "ingest.timeout.batch-size=1",
        "ingest.timeout.error-message=TEST_TIMEOUT_BS"
})
@Tag("it")
class IngestTimeoutSchedulerBatchSizeIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IngestTimeoutScheduler scheduler;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("TRUNCATE TABLE outbox_events, ingest_jobs, resource_tags, tags, links, documents, resources RESTART IDENTITY CASCADE");
    }

    @Test
    void sweep_respectsBatchSize_updatesOnlyOnePerRun() {
        long oldest = insertLinkResource("https://example.com/oldest", "example.com");
        insertProcessingJob(oldest);
        jdbc.update("UPDATE ingest_jobs SET updated_at = NOW() - INTERVAL '9 seconds' WHERE resource_id = ?", oldest);

        long middle = insertLinkResource("https://example.com/middle", "example.com");
        insertProcessingJob(middle);
        jdbc.update("UPDATE ingest_jobs SET updated_at = NOW() - INTERVAL '7 seconds' WHERE resource_id = ?", middle);

        long newest = insertLinkResource("https://example.com/newest", "example.com");
        insertProcessingJob(newest);
        jdbc.update("UPDATE ingest_jobs SET updated_at = NOW() - INTERVAL '5 seconds' WHERE resource_id = ?", newest);

        scheduler.sweepStaleProcessingJobs();

        assertEquals("failed", jdbc.queryForObject("SELECT status FROM ingest_jobs WHERE resource_id = ?", String.class, oldest));
        assertEquals("timeout", jdbc.queryForObject("SELECT stage FROM ingest_jobs WHERE resource_id = ?", String.class, oldest));
        assertEquals("TEST_TIMEOUT_BS", jdbc.queryForObject("SELECT error_message FROM ingest_jobs WHERE resource_id = ?", String.class, oldest));

        assertEquals("processing", jdbc.queryForObject("SELECT status FROM ingest_jobs WHERE resource_id = ?", String.class, middle));
        assertEquals("embedding", jdbc.queryForObject("SELECT stage FROM ingest_jobs WHERE resource_id = ?", String.class, middle));

        assertEquals("processing", jdbc.queryForObject("SELECT status FROM ingest_jobs WHERE resource_id = ?", String.class, newest));
        assertEquals("embedding", jdbc.queryForObject("SELECT stage FROM ingest_jobs WHERE resource_id = ?", String.class, newest));

        scheduler.sweepStaleProcessingJobs();
        assertEquals("failed", jdbc.queryForObject("SELECT status FROM ingest_jobs WHERE resource_id = ?", String.class, middle));
        assertEquals("timeout", jdbc.queryForObject("SELECT stage FROM ingest_jobs WHERE resource_id = ?", String.class, middle));

        assertEquals("processing", jdbc.queryForObject("SELECT status FROM ingest_jobs WHERE resource_id = ?", String.class, newest));

        scheduler.sweepStaleProcessingJobs();
        assertEquals("failed", jdbc.queryForObject("SELECT status FROM ingest_jobs WHERE resource_id = ?", String.class, newest));
        assertEquals("timeout", jdbc.queryForObject("SELECT stage FROM ingest_jobs WHERE resource_id = ?", String.class, newest));
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

    private void insertProcessingJob(long resourceId) {
        jdbc.update(
                """
                INSERT INTO ingest_jobs (resource_id, resource_type, title, status, stage)
                VALUES (?, 'link', 'test title', 'processing', 'embedding')
                """,
                resourceId
        );
    }
}
