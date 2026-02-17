package backend.resource;

import backend.test.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("it")
class ResourceUpdateApiIT extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("TRUNCATE TABLE outbox_events, ingest_jobs, resource_tags, tags, links, documents, resources RESTART IDENTITY CASCADE");
    }

    @Test
    void patchResource_updatesMetadata_andEnqueuesResourceIndex() throws Exception {
        long resourceId = insertLinkResource("old title", "old memo", "todo", false, "https://old.example.com/a", "old.example.com");
        setTags(resourceId, List.of("alpha", "beta"));

        mvc.perform(patch("/api/resources/{resourceId}", resourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "  new title  ",
                                  "memo": "new memo",
                                  "tags": ["zeta", "alpha"],
                                  "status": "done",
                                  "isPinned": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId", is((int) resourceId)))
                .andExpect(jsonPath("$.updated", is(true)))
                .andExpect(jsonPath("$.resourceType", is("link")))
                .andExpect(jsonPath("$.title", is("new title")))
                .andExpect(jsonPath("$.memo", is("new memo")))
                .andExpect(jsonPath("$.status", is("done")))
                .andExpect(jsonPath("$.isPinned", is(true)))
                .andExpect(jsonPath("$.url", is("https://old.example.com/a")))
                .andExpect(jsonPath("$.tags[0]", is("alpha")))
                .andExpect(jsonPath("$.tags[1]", is("zeta")));

        assertEquals("new title", jdbc.queryForObject("SELECT title FROM resources WHERE id = ?", String.class, resourceId));
        assertEquals("new memo", jdbc.queryForObject("SELECT memo FROM resources WHERE id = ?", String.class, resourceId));
        assertEquals("done", jdbc.queryForObject("SELECT status FROM resources WHERE id = ?", String.class, resourceId));
        assertEquals(true, jdbc.queryForObject("SELECT is_pinned FROM resources WHERE id = ?", Boolean.class, resourceId));
        assertEquals(List.of("alpha", "zeta"), findTagNames(resourceId));

        assertEquals(1L, countResourceIndexOutbox(resourceId));
    }

    @Test
    void patchResource_whenLinkUrlUpdated_updatesDomainToo() throws Exception {
        long resourceId = insertLinkResource("title", "memo", "todo", false, "https://old.example.com", "old.example.com");

        mvc.perform(patch("/api/resources/{resourceId}", resourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://www.docs.security.example.org/path"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId", is((int) resourceId)))
                .andExpect(jsonPath("$.updated", is(true)))
                .andExpect(jsonPath("$.url", is("https://www.docs.security.example.org/path")));

        assertEquals(
                "https://www.docs.security.example.org/path",
                jdbc.queryForObject("SELECT url FROM links WHERE resource_id = ?", String.class, resourceId)
        );
        assertEquals(
                "docs.security.example.org",
                jdbc.queryForObject("SELECT domain FROM links WHERE resource_id = ?", String.class, resourceId)
        );
        assertEquals(1L, countResourceIndexOutbox(resourceId));
    }

    @Test
    void patchResource_whenTagsEmpty_removesAllTags() throws Exception {
        long resourceId = insertLinkResource("title", "memo", "todo", false, "https://example.com", "example.com");
        setTags(resourceId, List.of("a", "b"));

        mvc.perform(patch("/api/resources/{resourceId}", resourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tags": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated", is(true)))
                .andExpect(jsonPath("$.tags.length()", is(0)));

        assertEquals(List.of(), findTagNames(resourceId));
        assertEquals(1L, countResourceIndexOutbox(resourceId));
    }

    @Test
    void patchResource_whenNoChange_returnsUpdatedFalse_andDoesNotEnqueueOutbox() throws Exception {
        long resourceId = insertLinkResource("title", "memo", "todo", false, "https://example.com", "example.com");

        mvc.perform(patch("/api/resources/{resourceId}", resourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "todo"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated", is(false)))
                .andExpect(jsonPath("$.status", is("todo")));

        assertEquals(0L, countResourceIndexOutbox(resourceId));
    }

    @Test
    void patchResourceStatus_usesUnifiedPath_andEnqueuesOutboxOnChange() throws Exception {
        long resourceId = insertLinkResource("title", "memo", "todo", false, "https://example.com", "example.com");

        mvc.perform(patch("/api/resources/{resourceId}/status", resourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "done"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId", is((int) resourceId)))
                .andExpect(jsonPath("$.status", is("done")));

        assertEquals("done", jdbc.queryForObject("SELECT status FROM resources WHERE id = ?", String.class, resourceId));
        assertEquals(1L, countResourceIndexOutbox(resourceId));
    }

    @Test
    void patchResource_whenResourceMissing_returns404() throws Exception {
        mvc.perform(patch("/api/resources/{resourceId}", 9999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "x"
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchResource_whenInvalidStatus_returns400() throws Exception {
        long resourceId = insertLinkResource("title", "memo", "todo", false, "https://example.com", "example.com");

        mvc.perform(patch("/api/resources/{resourceId}", resourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "blocked"
                                }
                                """))
                .andExpect(status().isBadRequest());

        assertEquals("todo", jdbc.queryForObject("SELECT status FROM resources WHERE id = ?", String.class, resourceId));
        assertEquals(0L, countResourceIndexOutbox(resourceId));
    }

    @Test
    void patchResource_whenTitleBlank_returns400() throws Exception {
        long resourceId = insertLinkResource("title", "memo", "todo", false, "https://example.com", "example.com");

        mvc.perform(patch("/api/resources/{resourceId}", resourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "   "
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchResource_whenDocumentUrlRequested_returns400() throws Exception {
        long resourceId = insertDocumentResource("doc title", "memo", "todo", false);

        mvc.perform(patch("/api/resources/{resourceId}", resourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://example.com/new"
                                }
                                """))
                .andExpect(status().isBadRequest());

        assertEquals(0L, countResourceIndexOutbox(resourceId));
    }

    @Test
    void listResources_whenPinnedFilterApplied_returnsOnlyPinnedRows() throws Exception {
        long pinnedId = insertLinkResource("pinned", "memo", "todo", true, "https://pin.example.com", "pin.example.com");
        insertLinkResource("normal", "memo", "todo", false, "https://normal.example.com", "normal.example.com");

        mvc.perform(get("/api/resources")
                        .param("isPinned", "true")
                        .param("page", "0")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.items[0].resourceId", is((int) pinnedId)))
                .andExpect(jsonPath("$.items[0].isPinned", is(true)));
    }

    private long insertLinkResource(String title, String memo, String status, boolean pinned, String url, String domain) {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO resources (type, title, memo, status, is_pinned)
                VALUES ('link', ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                title,
                memo,
                status,
                pinned
        );
        if (id == null) throw new IllegalStateException("failed to insert resources row");

        jdbc.update(
                "INSERT INTO links (resource_id, url, domain) VALUES (?, ?, ?)",
                id,
                url,
                domain
        );
        return id;
    }

    private long insertDocumentResource(String title, String memo, String status, boolean pinned) {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO resources (type, title, memo, status, is_pinned)
                VALUES ('document', ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                title,
                memo,
                status,
                pinned
        );
        if (id == null) throw new IllegalStateException("failed to insert resources row");

        jdbc.update(
                """
                INSERT INTO documents (resource_id, file_path, mime_type, file_size, sha256)
                VALUES (?, ?, ?, ?, ?)
                """,
                id,
                "build/test-uploads/" + id + "/original.pdf",
                "application/pdf",
                10L,
                "sha-" + id
        );
        return id;
    }

    private void setTags(long resourceId, List<String> tags) {
        for (String tag : tags) {
            jdbc.update("INSERT INTO tags (name) VALUES (?) ON CONFLICT DO NOTHING", tag);
            Long tagId = jdbc.queryForObject("SELECT id FROM tags WHERE name = ?", Long.class, tag);
            jdbc.update(
                    "INSERT INTO resource_tags (resource_id, tag_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    resourceId,
                    tagId
            );
        }
    }

    private List<String> findTagNames(long resourceId) {
        return jdbc.query(
                """
                SELECT t.name
                FROM resource_tags rt
                JOIN tags t ON t.id = rt.tag_id
                WHERE rt.resource_id = ?
                ORDER BY t.name
                """,
                (rs, rowNum) -> rs.getString("name"),
                resourceId
        );
    }

    private long countResourceIndexOutbox(long resourceId) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM outbox_events
                WHERE aggregate_id = ?
                  AND event_type = 'RESOURCE_INDEX'
                  AND published_at IS NULL
                """,
                Long.class,
                resourceId
        );
        return count == null ? 0L : count;
    }
}
