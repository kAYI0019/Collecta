package backend.resource;

import backend.test.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("it")
class ResourceFileApiIT extends IntegrationTestBase {

    private static final Path TEST_UPLOAD_DIR = Path.of("build/test-uploads").toAbsolutePath().normalize();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        jdbc.execute("TRUNCATE TABLE outbox_events, ingest_jobs, resource_tags, tags, links, documents, resources RESTART IDENTITY CASCADE");
        deleteDirectoryQuietly(TEST_UPLOAD_DIR);
    }

    @AfterEach
    void cleanupFiles() {
        deleteDirectoryQuietly(TEST_UPLOAD_DIR);
    }

    @Test
    void openFile_whenDocumentExists_returnsInlinePdf() throws Exception {
        long resourceId = insertDocumentResource();
        Path filePath = writeDocumentFile(resourceId, "original.pdf", "%PDF-1.4 mock");
        insertDocumentRow(resourceId, filePath, "application/pdf");

        mvc.perform(get("/api/resources/{resourceId}/file", resourceId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("application/pdf")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("inline")))
                .andExpect(content().bytes("%PDF-1.4 mock".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void openFile_whenResourceMissing_returns404() throws Exception {
        mvc.perform(get("/api/resources/{resourceId}/file", 9999))
                .andExpect(status().isNotFound());
    }

    @Test
    void openFile_whenNotDocument_returns404() throws Exception {
        long resourceId = insertLinkResource();

        mvc.perform(get("/api/resources/{resourceId}/file", resourceId))
                .andExpect(status().isNotFound());
    }

    private long insertDocumentResource() {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO resources (type, title, memo, status, is_pinned)
                VALUES ('document', 'doc', '', 'todo', false)
                RETURNING id
                """,
                Long.class
        );
        if (id == null) throw new IllegalStateException("failed to insert document resource");
        return id;
    }

    private long insertLinkResource() {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO resources (type, title, memo, status, is_pinned)
                VALUES ('link', 'link', '', 'todo', false)
                RETURNING id
                """,
                Long.class
        );
        if (id == null) throw new IllegalStateException("failed to insert link resource");

        jdbc.update(
                "INSERT INTO links (resource_id, url, domain) VALUES (?, ?, ?)",
                id,
                "https://example.com",
                "example.com"
        );
        return id;
    }

    private Path writeDocumentFile(long resourceId, String filename, String body) throws IOException {
        Path dir = TEST_UPLOAD_DIR.resolve(Long.toString(resourceId));
        Files.createDirectories(dir);
        Path filePath = dir.resolve(filename);
        Files.writeString(filePath, body, StandardCharsets.UTF_8);
        return filePath;
    }

    private void insertDocumentRow(long resourceId, Path filePath, String mimeType) throws IOException {
        jdbc.update(
                """
                INSERT INTO documents (resource_id, file_path, mime_type, file_size, sha256)
                VALUES (?, ?, ?, ?, ?)
                """,
                resourceId,
                filePath.toAbsolutePath().normalize().toString(),
                mimeType,
                Files.size(filePath),
                "sha-" + resourceId
        );
    }

    private static void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}

