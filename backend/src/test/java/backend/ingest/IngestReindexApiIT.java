package backend.ingest;

import backend.test.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("it")
class IngestReindexApiIT extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("TRUNCATE TABLE outbox_events, ingest_jobs, resource_tags, tags, links, documents, resources RESTART IDENTITY CASCADE");
    }

    @Test
    void reindex_whenDone_resetsJobToQueued_andEnqueuesOutboxEvent() throws Exception {
        long resourceId = insertLinkResource("https://example.com/reindex-done", "example.com");
        insertIngestJob(resourceId, "link", "done", null);

        mvc.perform(post("/api/ingest/{resourceId}/reindex", resourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId", is((int) resourceId)))
                .andExpect(jsonPath("$.status", is("queued")));

        String jobStatus = jdbc.queryForObject(
                "SELECT status FROM ingest_jobs WHERE resource_id = ?",
                String.class,
                resourceId
        );
        assertEquals("queued", jobStatus);

        Long outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'RESOURCE_INDEX' AND published_at IS NULL",
                Long.class,
                resourceId
        );
        assertEquals(1L, outboxCount);
    }

    @Test
    void reindex_whenProcessing_returns409_andDoesNotEnqueueOutboxEvent() throws Exception {
        long resourceId = insertLinkResource("https://example.com/reindex-processing", "example.com");
        insertIngestJob(resourceId, "link", "processing", null);

        mvc.perform(post("/api/ingest/{resourceId}/reindex", resourceId))
                .andExpect(status().isConflict());

        Long outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?",
                Long.class,
                resourceId
        );
        assertEquals(0L, outboxCount);
    }

    @Test
    void reindexLinks_enqueuesNonProcessingLinkJobs_only() throws Exception {
        long doneId = insertLinkResource("https://example.com/reindex-links-done", "example.com");
        insertIngestJob(doneId, "link", "done", null);

        long failedId = insertLinkResource("https://example.com/reindex-links-failed", "example.com");
        insertIngestJob(failedId, "link", "failed", "old error");

        long processingId = insertLinkResource("https://example.com/reindex-links-processing", "example.com");
        insertIngestJob(processingId, "link", "processing", null);

        mvc.perform(post("/api/ingest/reindex-links").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected", is(2)))
                .andExpect(jsonPath("$.enqueued", is(2)))
                .andExpect(jsonPath("$.skipped", is(0)));

        assertEquals("queued", jdbc.queryForObject(
                "SELECT status FROM ingest_jobs WHERE resource_id = ?",
                String.class,
                doneId
        ));
        assertEquals("queued", jdbc.queryForObject(
                "SELECT status FROM ingest_jobs WHERE resource_id = ?",
                String.class,
                failedId
        ));
        assertEquals("processing", jdbc.queryForObject(
                "SELECT status FROM ingest_jobs WHERE resource_id = ?",
                String.class,
                processingId
        ));

        Long doneOutboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'RESOURCE_INDEX' AND published_at IS NULL",
                Long.class,
                doneId
        );
        Long failedOutboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'RESOURCE_INDEX' AND published_at IS NULL",
                Long.class,
                failedId
        );
        Long processingOutboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'RESOURCE_INDEX' AND published_at IS NULL",
                Long.class,
                processingId
        );

        assertEquals(1L, doneOutboxCount);
        assertEquals(1L, failedOutboxCount);
        assertEquals(0L, processingOutboxCount);
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

    private void insertIngestJob(long resourceId, String resourceType, String status, String errorMessage) {
        jdbc.update(
                """
                INSERT INTO ingest_jobs (resource_id, resource_type, title, status, stage, total_units, processed_units, error_message)
                VALUES (?, ?, 'test title', ?, 'stage1', 10, 3, ?)
                """,
                resourceId, resourceType, status, errorMessage
        );
    }
}
