package backend.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class IngestService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String basePath;

    public IngestService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${storage.base-path}") String basePath
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.basePath = basePath;
    }

    @Transactional
    public long ingestDocument(
            MultipartFile file,
            String title,
            String memo,
            String status,
            boolean isPinned,
            List<String> tags
    ) {
        String safeTitle = (title == null || title.isBlank()) ? file.getOriginalFilename() : title;
        String safeMemo = (memo == null ? "" : memo);
        String safeStatus = (status == null || status.isBlank()) ? "todo" : status;

        ResourceRow resource = insertResource("document", safeTitle, safeMemo, safeStatus, isPinned);
        insertIngestJob(resource.id(), "document", safeTitle);

        Path baseDir = Path.of(basePath).toAbsolutePath().normalize();
        Path resourceDir = baseDir.resolve(Long.toString(resource.id()));
        try {
            Files.createDirectories(resourceDir);
        } catch (IOException e) {
            throw new IllegalStateException("failed to create resource directory", e);
        }

        String ext = fileExtension(file.getOriginalFilename());
        Path dest = resourceDir.resolve("original" + ext);

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("failed to save file", e);
        }

        long size;
        try {
            size = Files.size(dest);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read file size", e);
        }

        String mimeType = file.getContentType();
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = probeMimeType(dest);
        }

        String sha256 = sha256Hex(dest);

        jdbc.update(
                """
                INSERT INTO documents (resource_id, file_path, mime_type, file_size, sha256)
                VALUES (?, ?, ?, ?, ?)
                """,
                resource.id(),
                dest.toString(),
                mimeType,
                size,
                sha256
        );

        attachTags(resource.id(), tags);
        publishIndexEvent(resource, "document", null, tags, safeStatus, isPinned, Map.of(
                "file_path", dest.toString(),
                "mime_type", mimeType,
                "file_name", file.getOriginalFilename()
        ), null);

        return resource.id();
    }

    @Transactional
    public long ingestLink(
            String url,
            String title,
            String memo,
            String status,
            boolean isPinned,
            List<String> tags
    ) {
        String safeTitle = (title == null || title.isBlank()) ? url : title;
        String safeMemo = (memo == null ? "" : memo);
        String safeStatus = (status == null || status.isBlank()) ? "todo" : status;
        String domain = extractDomain(url);

        ResourceRow resource = insertResource("link", safeTitle, safeMemo, safeStatus, isPinned);
        insertIngestJob(resource.id(), "link", safeTitle);

        jdbc.update(
                """
                INSERT INTO links (resource_id, url, domain)
                VALUES (?, ?, ?)
                """,
                resource.id(),
                url,
                domain
        );

        attachTags(resource.id(), tags);
        publishIndexEvent(resource, "link", domain, tags, safeStatus, isPinned, null, Map.of(
                "title", safeTitle,
                "memo", safeMemo,
                "tags", tags
        ));

        return resource.id();
    }

    private ResourceRow insertResource(String type, String title, String memo, String status, boolean isPinned) {
        return jdbc.queryForObject(
                """
                INSERT INTO resources (type, title, memo, status, is_pinned)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id, created_at
                """,
                (rs, rowNum) -> new ResourceRow(
                        rs.getLong("id"),
                        rs.getObject("created_at", OffsetDateTime.class)
                ),
                type, title, memo, status, isPinned
        );
    }

    private void insertIngestJob(long resourceId, String resourceType, String title) {
        jdbc.update(
                """
                INSERT INTO ingest_jobs (resource_id, resource_type, title, status)
                VALUES (?, ?, ?, 'queued')
                ON CONFLICT (resource_id) DO NOTHING
                """,
                resourceId, resourceType, title
        );
    }

    private void attachTags(long resourceId, List<String> tags) {
        if (tags == null || tags.isEmpty()) return;
        List<String> cleaned = tags.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        if (cleaned.isEmpty()) return;

        for (String tag : cleaned) {
            jdbc.update("INSERT INTO tags (name) VALUES (?) ON CONFLICT DO NOTHING", tag);
        }

        List<Long> tagIds = jdbc.query(
                "SELECT id FROM tags WHERE name = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", cleaned.toArray())),
                (rs, rowNum) -> rs.getLong("id")
        );

        for (Long tagId : tagIds) {
            jdbc.update(
                    "INSERT INTO resource_tags (resource_id, tag_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    resourceId, tagId
            );
        }
    }

    private void publishIndexEvent(
            ResourceRow resource,
            String resourceType,
            String domain,
            List<String> tags,
            String status,
            boolean isPinned,
            Map<String, Object> document,
            Map<String, Object> link
    ) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("resource_id", resource.id());
        payload.put("resource_type", resourceType);
        payload.put("domain", domain);
        payload.put("tags", (tags == null ? List.of() : tags));
        payload.put("status", status);
        payload.put("is_pinned", isPinned);
        payload.put("created_at", resource.createdAt() == null ? null : resource.createdAt().toString());
        payload.put("document", document);
        payload.put("link", link);

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new IllegalStateException("failed to serialize outbox payload", e);
        }

        jdbc.update(
                """
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                VALUES ('resource', ?, 'RESOURCE_INDEX', ?::jsonb)
                """,
                resource.id(),
                json
        );
    }

    private static String extractDomain(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return "";
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }

    private static String fileExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "";
        return filename.substring(dot);
    }

    private static String probeMimeType(Path path) {
        try {
            String mime = Files.probeContentType(path);
            return mime == null ? "application/octet-stream" : mime;
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private static String sha256Hex(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private record ResourceRow(long id, OffsetDateTime createdAt) {}
}
