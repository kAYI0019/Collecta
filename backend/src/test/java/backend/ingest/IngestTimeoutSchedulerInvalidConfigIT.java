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
        "ingest.timeout.stale-ms=0",
        "ingest.timeout.batch-size=100",
        "ingest.timeout.error-message=SHOULD_NOT_APPLY"
})
@Tag("it")
class IngestTimeoutSchedulerInvalidConfigIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IngestTimeoutScheduler scheduler;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("TRUNCATE TABLE outbox_events, ingest_jobs, resource_tags, tags, links, documents, resources RESTART IDENTITY CASCADE");
    }

    @Test
    void sweep_whenStaleMsNonPositive_doesNothing() {
        long id = insertLinkResource("https://example.com/invalid", "example.com");
        jdbc.update(
                "INSERT INTO ingest_jobs (resource_id, resource_type, title, status, stage) VALUES (?, 'link', 't', 'processing', 'embedding')",
                id
        );
        jdbc.update("UPDATE ingest_jobs SET updated_at = NOW() - INTERVAL '10 seconds' WHERE resource_id = ?", id);

        scheduler.sweepStaleProcessingJobs();

        assertEquals("processing", jdbc.queryForObject("SELECT status FROM ingest_jobs WHERE resource_id = ?", String.class, id));
        assertEquals("embedding", jdbc.queryForObject("SELECT stage FROM ingest_jobs WHERE resource_id = ?", String.class, id));
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
}
