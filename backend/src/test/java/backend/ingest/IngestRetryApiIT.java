package backend.ingest;

import backend.test.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("it")
class IngestRetryApiIT extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("TRUNCATE TABLE outbox_events, ingest_jobs, resource_tags, tags, links, documents, resources RESTART IDENTITY CASCADE");
    }

    @Test
    void retry_whenFailed_resetsJobToQueued_andEnqueuesOutboxEvent() throws Exception {
        long resourceId = insertLinkResource("https://example.com/a", "example.com");
        insertIngestJob(resourceId, "link", "failed", "worker error");

        mvc.perform(post("/api/ingest/{resourceId}/retry", resourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId", is((int) resourceId)))
                .andExpect(jsonPath("$.status", is("queued")));

        Long queued = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM ingest_jobs
                WHERE resource_id = ?
                  AND status = 'queued'
                  AND stage IS NULL
                  AND total_units IS NULL
                  AND processed_units IS NULL
                  AND error_message IS NULL
                """,
                Long.class,
                resourceId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1L, queued);

        Long outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'resource' AND aggregate_id = ? AND event_type = 'RESOURCE_INDEX' AND published_at IS NULL",
                Long.class,
                resourceId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1L, outboxCount);
    }

    @Test
    void retry_whenCancelled_resetsJobToQueued_andEnqueuesOutboxEvent() throws Exception {
        long resourceId = insertLinkResource("https://example.com/c", "example.com");
        insertIngestJob(resourceId, "link", "cancelled", "user cancelled");

        mvc.perform(post("/api/ingest/{resourceId}/retry", resourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId", is((int) resourceId)))
                .andExpect(jsonPath("$.status", is("queued")));

        Long queued = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM ingest_jobs
                WHERE resource_id = ?
                  AND status = 'queued'
                  AND stage IS NULL
                  AND total_units IS NULL
                  AND processed_units IS NULL
                  AND error_message IS NULL
                """,
                Long.class,
                resourceId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1L, queued);

        Long outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'resource' AND aggregate_id = ? AND event_type = 'RESOURCE_INDEX' AND published_at IS NULL",
                Long.class,
                resourceId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1L, outboxCount);
    }

    @Test
    void retry_whenAlreadyRetried_returns409_andDoesNotEnqueueSecondOutboxEvent() throws Exception {
        long resourceId = insertLinkResource("https://example.com/r1", "example.com");
        insertIngestJob(resourceId, "link", "failed", "worker error");

        mvc.perform(post("/api/ingest/{resourceId}/retry", resourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId", is((int) resourceId)))
                .andExpect(jsonPath("$.status", is("queued")));

        mvc.perform(post("/api/ingest/{resourceId}/retry", resourceId))
                .andExpect(status().isConflict());

        String status = jdbc.queryForObject(
                "SELECT status FROM ingest_jobs WHERE resource_id = ?",
                String.class,
                resourceId
        );
        org.junit.jupiter.api.Assertions.assertEquals("queued", status);

        Long outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'resource' AND aggregate_id = ? AND event_type = 'RESOURCE_INDEX' AND published_at IS NULL",
                Long.class,
                resourceId
        );
        org.junit.jupiter.api.Assertions.assertEquals(1L, outboxCount);
    }

    @Test
    void retry_whenProcessing_returns409() throws Exception {
        long resourceId = insertLinkResource("https://example.com/b", "example.com");
        insertIngestJob(resourceId, "link", "processing", null);

        mvc.perform(post("/api/ingest/{resourceId}/retry", resourceId))
                .andExpect(status().isConflict());

        Long outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?",
                Long.class,
                resourceId
        );
        org.junit.jupiter.api.Assertions.assertEquals(0L, outboxCount);
    }

    @Test
    void retry_whenQueued_returns409() throws Exception {
        long resourceId = insertLinkResource("https://example.com/q", "example.com");
        insertIngestJob(resourceId, "link", "queued", null);

        mvc.perform(post("/api/ingest/{resourceId}/retry", resourceId))
                .andExpect(status().isConflict());
    }

    @Test
    void retry_whenDone_returns409() throws Exception {
        long resourceId = insertLinkResource("https://example.com/d", "example.com");
        insertIngestJob(resourceId, "link", "done", null);

        mvc.perform(post("/api/ingest/{resourceId}/retry", resourceId))
                .andExpect(status().isConflict());
    }

    @Test
    void retry_document_whenFailed_enqueuesResourceIndexOutboxWithDocumentPayload() throws Exception {
        long resourceId = insertDocumentResource("build/test-uploads/1/original.pdf", "application/pdf");
        insertIngestJob(resourceId, "document", "failed", "worker error");

        mvc.perform(post("/api/ingest/{resourceId}/retry", resourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId", is((int) resourceId)))
                .andExpect(jsonPath("$.status", is("queued")));

        String resourceType = jdbc.queryForObject(
                "SELECT payload->>'resource_type' FROM outbox_events WHERE aggregate_id = ? AND event_type = 'RESOURCE_INDEX' ORDER BY id DESC LIMIT 1",
                String.class,
                resourceId
        );
        org.junit.jupiter.api.Assertions.assertEquals("document", resourceType);

        String filePath = jdbc.queryForObject(
                "SELECT payload->'document'->>'file_path' FROM outbox_events WHERE aggregate_id = ? AND event_type = 'RESOURCE_INDEX' ORDER BY id DESC LIMIT 1",
                String.class,
                resourceId
        );
        org.junit.jupiter.api.Assertions.assertEquals("build/test-uploads/1/original.pdf", filePath);
    }

    @Test
    void retry_whenResourceMissing_returns404() throws Exception {
        mvc.perform(post("/api/ingest/{resourceId}/retry", 9999))
                .andExpect(status().isNotFound());
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

    private long insertDocumentResource(String filePath, String mimeType) {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO resources (type, title, memo, status, is_pinned)
                VALUES ('document', 'test doc', '', 'todo', false)
                RETURNING id
                """,
                Long.class
        );
        if (id == null) throw new IllegalStateException("failed to insert resources row");

        jdbc.update(
                "INSERT INTO documents (resource_id, file_path, mime_type, file_size, sha256) VALUES (?, ?, ?, 1, 'sha')",
                id, filePath, mimeType
        );
        return id;
    }
}
