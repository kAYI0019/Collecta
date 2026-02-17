package backend.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class ResourceService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String basePath;

    public ResourceService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${storage.base-path}") String basePath
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.basePath = basePath;
    }

    @Transactional
    public boolean deleteResource(long resourceId) {
        int updated = jdbc.update("DELETE FROM resources WHERE id = ?", resourceId);

        // 파일 삭제는 best-effort (DB 트랜잭션과 분리)
        Path resourceDir = Path.of(basePath).toAbsolutePath().normalize().resolve(Long.toString(resourceId));
        deleteDirectoryQuietly(resourceDir);

        return updated > 0;
    }

    public Optional<DocumentFile> findDocumentFile(long resourceId) {
        List<DocumentFileRow> rows = jdbc.query(
                """
                SELECT d.file_path, d.mime_type
                FROM documents d
                WHERE d.resource_id = ?
                """,
                (rs, rowNum) -> new DocumentFileRow(
                        rs.getString("file_path"),
                        rs.getString("mime_type")
                ),
                resourceId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Path baseDir = Path.of(basePath).toAbsolutePath().normalize();
        DocumentFileRow row = rows.get(0);
        Path filePath = Path.of(row.filePath()).toAbsolutePath().normalize();

        if (!filePath.startsWith(baseDir)) {
            throw new IllegalStateException("document path is outside storage base path: " + resourceId);
        }
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return Optional.empty();
        }

        String filename = filePath.getFileName() == null ? "file" : filePath.getFileName().toString();
        long size;
        try {
            size = Files.size(filePath);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read file size: " + filePath, e);
        }

        return Optional.of(new DocumentFile(
                filePath,
                row.mimeType(),
                filename,
                size
        ));
    }

    public ResourceListResponse listResources(
            List<String> statuses,
            String resourceType,
            Boolean isPinned,
            int page,
            int pageSize
    ) {
        int safePage = Math.max(0, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        List<String> safeStatuses = sanitizeStatuses(statuses);
        String safeResourceType = sanitizeResourceType(resourceType);
        Boolean safePinned = isPinned;
        boolean filteredByStatus = !safeStatuses.isEmpty();
        boolean filteredByType = safeResourceType != null;
        boolean filteredByPinned = safePinned != null;

        String whereClause = buildWhereClause(filteredByStatus, filteredByType, filteredByPinned);

        String countSql = "SELECT COUNT(*) FROM resources r" + whereClause;

        long total = jdbc.query(
                countSql,
                ps -> bindListFilterParams(ps, safeStatuses, safeResourceType, safePinned),
                rs -> rs.next() ? rs.getLong(1) : 0L
        );

        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) safePageSize);
        int offset = safePage * safePageSize;
        if (offset >= total) {
            return new ResourceListResponse(List.of(), safePage, safePageSize, total, totalPages);
        }

        String listSqlBase = """
                SELECT
                    r.id                      AS resource_id,
                    r.type                    AS type,
                    r.title                   AS title,
                    r.memo                    AS memo,
                    r.status                  AS status,
                    r.is_pinned               AS is_pinned,
                    r.created_at              AS created_at,
                    l.url                     AS url,
                    l.domain                  AS domain,
                    d.mime_type               AS mime_type,
                    d.file_size               AS file_size,
                    j.status                  AS ingest_status,
                    j.stage                   AS ingest_stage,
                    j.total_units             AS total_units,
                    j.processed_units         AS processed_units,
                    j.error_message           AS error_message,
                    j.updated_at              AS ingest_updated_at,
                    COALESCE(array_agg(t.name) FILTER (WHERE t.name IS NOT NULL), ARRAY[]::text[]) AS tags
                FROM resources r
                LEFT JOIN links l ON l.resource_id = r.id
                LEFT JOIN documents d ON d.resource_id = r.id
                LEFT JOIN ingest_jobs j ON j.resource_id = r.id
                LEFT JOIN resource_tags rt ON rt.resource_id = r.id
                LEFT JOIN tags t ON t.id = rt.tag_id
                """;

        String listSqlSuffix = """
                GROUP BY
                    r.id, r.type, r.title, r.memo, r.status, r.is_pinned, r.created_at,
                    l.url, l.domain, d.mime_type, d.file_size,
                    j.status, j.stage, j.total_units, j.processed_units, j.error_message, j.updated_at
                ORDER BY r.is_pinned DESC, r.created_at DESC, r.id DESC
                LIMIT ? OFFSET ?
                """;

        String listSql = listSqlBase + whereClause + "\n" + listSqlSuffix;

        List<ResourceListItem> items = jdbc.query(
                listSql,
                ps -> {
                    int idx = bindListFilterParams(ps, safeStatuses, safeResourceType, safePinned);
                    ps.setInt(idx++, safePageSize);
                    ps.setInt(idx, offset);
                },
                (rs, rowNum) -> new ResourceListItem(
                        rs.getLong("resource_id"),
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("memo"),
                        rs.getString("status"),
                        rs.getBoolean("is_pinned"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("url"),
                        rs.getString("domain"),
                        rs.getString("mime_type"),
                        (rs.getObject("file_size") == null ? null : rs.getLong("file_size")),
                        readTextArray(rs, "tags"),
                        rs.getString("ingest_status"),
                        rs.getString("ingest_stage"),
                        (Integer) rs.getObject("total_units"),
                        (Integer) rs.getObject("processed_units"),
                        rs.getString("error_message"),
                        rs.getObject("ingest_updated_at", OffsetDateTime.class)
                )
        );

        return new ResourceListResponse(items, safePage, safePageSize, total, totalPages);
    }

    public StatusUpdateOutcome updateResourceStatus(long resourceId, String status) {
        try {
            Optional<ResourceUpdateResponse> responseOpt = updateResource(
                    resourceId,
                    ResourcePatch.statusOnly(status)
            );
            if (responseOpt.isEmpty()) {
                return new StatusUpdateOutcome(StatusUpdateResult.NOT_FOUND, null);
            }
            return new StatusUpdateOutcome(StatusUpdateResult.UPDATED, responseOpt.get().status());
        } catch (IllegalArgumentException e) {
            return new StatusUpdateOutcome(StatusUpdateResult.INVALID_STATUS, null);
        }
    }

    @Transactional
    public Optional<ResourceUpdateResponse> updateResource(long resourceId, ResourcePatch patch) {
        ResourcePatch safePatch = (patch == null) ? ResourcePatch.empty() : patch;

        Optional<ResourceSnapshot> currentOpt = findResourceSnapshot(resourceId);
        if (currentOpt.isEmpty()) {
            return Optional.empty();
        }

        ResourceSnapshot current = currentOpt.get();
        List<String> currentTags = findTagsByResourceId(resourceId);

        String nextTitle = current.title();
        String nextMemo = current.memo();
        String nextStatus = current.status();
        boolean nextPinned = current.isPinned();
        String nextUrl = current.url();
        String nextDomain = current.domain();
        List<String> nextTags = currentTags;

        boolean resourcesChanged = false;
        boolean tagsChanged = false;
        boolean linkChanged = false;

        if (safePatch.hasTitle()) {
            if (safePatch.title() == null) {
                throw new IllegalArgumentException("title must not be null");
            }
            String normalizedTitle = safePatch.title().trim();
            if (normalizedTitle.isBlank()) {
                throw new IllegalArgumentException("title must not be blank");
            }
            if (!normalizedTitle.equals(current.title())) {
                nextTitle = normalizedTitle;
                resourcesChanged = true;
            }
        }

        if (safePatch.hasMemo()) {
            if (!Objects.equals(safePatch.memo(), current.memo())) {
                nextMemo = safePatch.memo();
                resourcesChanged = true;
            }
        }

        if (safePatch.hasStatus()) {
            String normalizedStatus = normalizeTaskStatus(safePatch.status());
            if (normalizedStatus == null) {
                throw new IllegalArgumentException("invalid status (allowed: todo, in_progress, done)");
            }
            if (!normalizedStatus.equals(current.status())) {
                nextStatus = normalizedStatus;
                resourcesChanged = true;
            }
        }

        if (safePatch.hasIsPinned()) {
            if (safePatch.isPinned() == null) {
                throw new IllegalArgumentException("isPinned must not be null");
            }
            if (safePatch.isPinned() != current.isPinned()) {
                nextPinned = safePatch.isPinned();
                resourcesChanged = true;
            }
        }

        if (safePatch.hasUrl()) {
            if (!"link".equals(current.resourceType())) {
                throw new IllegalArgumentException("url can be updated only for link resources");
            }
            if (safePatch.url() == null) {
                throw new IllegalArgumentException("url must not be null");
            }
            String normalizedUrl = safePatch.url().trim();
            if (normalizedUrl.isBlank()) {
                throw new IllegalArgumentException("url must not be blank");
            }
            String normalizedDomain = extractDomain(normalizedUrl);
            if (!Objects.equals(normalizedUrl, current.url()) || !Objects.equals(normalizedDomain, current.domain())) {
                nextUrl = normalizedUrl;
                nextDomain = normalizedDomain;
                linkChanged = true;
            }
        }

        if (safePatch.hasTags()) {
            List<String> normalizedTags = sanitizeTagsForUpdate(safePatch.tags());
            if (!currentTags.equals(normalizedTags)) {
                nextTags = normalizedTags;
                tagsChanged = true;
            }
        }

        boolean changed = resourcesChanged || tagsChanged || linkChanged;
        if (!changed) {
            return Optional.of(new ResourceUpdateResponse(
                    current.resourceId(),
                    false,
                    current.resourceType(),
                    current.title(),
                    current.memo(),
                    currentTags,
                    current.status(),
                    current.isPinned(),
                    current.url()
            ));
        }

        if (resourcesChanged) {
            jdbc.update(
                    """
                    UPDATE resources
                    SET title = ?, memo = ?, status = ?, is_pinned = ?, updated_at = NOW()
                    WHERE id = ?
                    """,
                    nextTitle,
                    nextMemo,
                    nextStatus,
                    nextPinned,
                    resourceId
            );
        } else {
            jdbc.update(
                    """
                    UPDATE resources
                    SET updated_at = NOW()
                    WHERE id = ?
                    """,
                    resourceId
            );
        }

        if (linkChanged) {
            jdbc.update(
                    """
                    UPDATE links
                    SET url = ?, domain = ?
                    WHERE resource_id = ?
                    """,
                    nextUrl,
                    nextDomain,
                    resourceId
            );
        }

        if (tagsChanged) {
            replaceTags(resourceId, nextTags);
        }

        ResourceSnapshot latest = findResourceSnapshot(resourceId)
                .orElseThrow(() -> new IllegalStateException("resource disappeared during update: " + resourceId));
        List<String> latestTags = tagsChanged ? nextTags : findTagsByResourceId(resourceId);
        publishResourceIndexEvent(latest, latestTags);

        return Optional.of(new ResourceUpdateResponse(
                latest.resourceId(),
                true,
                latest.resourceType(),
                latest.title(),
                latest.memo(),
                latestTags,
                latest.status(),
                latest.isPinned(),
                latest.url()
        ));
    }

    private static void deleteDirectoryQuietly(Path dir) {
        if (dir == null) return;
        if (!Files.exists(dir)) return;
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

    private static List<String> sanitizeStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return List.of();
        return statuses.stream()
                .map(s -> s == null ? "" : s.trim())
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private static String sanitizeResourceType(String resourceType) {
        if (resourceType == null) return null;
        String normalized = resourceType.trim().toLowerCase();
        if (normalized.isBlank()) return null;
        if (!normalized.equals("document") && !normalized.equals("link")) return null;
        return normalized;
    }

    private static String normalizeTaskStatus(String status) {
        if (status == null) return null;
        String normalized = status.trim().toLowerCase();
        if (normalized.isBlank()) return null;
        if (!normalized.equals("todo") && !normalized.equals("in_progress") && !normalized.equals("done")) {
            return null;
        }
        return normalized;
    }

    private static List<String> sanitizeTagsForUpdate(List<String> tags) {
        if (tags == null) return List.of();
        return tags.stream()
                .map(tag -> tag == null ? "" : tag.trim())
                .filter(tag -> !tag.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static String buildWhereClause(boolean hasStatusFilter, boolean hasTypeFilter, boolean hasPinnedFilter) {
        if (!hasStatusFilter && !hasTypeFilter && !hasPinnedFilter) return "";
        StringBuilder where = new StringBuilder(" WHERE ");
        boolean appended = false;
        if (hasStatusFilter) {
            where.append("r.status = ANY (?)");
            appended = true;
        }
        if (hasTypeFilter) {
            if (appended) {
                where.append(" AND ");
            }
            where.append("r.type = ?");
            appended = true;
        }
        if (hasPinnedFilter) {
            if (appended) {
                where.append(" AND ");
            }
            where.append("r.is_pinned = ?");
        }
        return where.toString();
    }

    private static int bindListFilterParams(
            java.sql.PreparedStatement ps,
            List<String> safeStatuses,
            String safeResourceType,
            Boolean safePinned
    ) throws SQLException {
        int idx = 1;
        if (safeStatuses != null && !safeStatuses.isEmpty()) {
            Array arr = ps.getConnection().createArrayOf("text", safeStatuses.toArray());
            ps.setArray(idx++, arr);
        }
        if (safeResourceType != null) {
            ps.setString(idx++, safeResourceType);
        }
        if (safePinned != null) {
            ps.setBoolean(idx++, safePinned);
        }
        return idx;
    }

    private static List<String> readTextArray(ResultSet rs, String col) throws SQLException {
        Array arr = rs.getArray(col);
        if (arr == null) return List.of();
        String[] v = (String[]) arr.getArray();
        if (v == null) return List.of();
        return Arrays.stream(v)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private Optional<ResourceSnapshot> findResourceSnapshot(long resourceId) {
        List<ResourceSnapshot> rows = jdbc.query(
                """
                SELECT r.id,
                       r.type,
                       r.title,
                       r.memo,
                       r.status,
                       r.is_pinned,
                       r.created_at,
                       l.url,
                       l.domain,
                       d.file_path,
                       d.mime_type
                FROM resources r
                LEFT JOIN links l ON l.resource_id = r.id
                LEFT JOIN documents d ON d.resource_id = r.id
                WHERE r.id = ?
                """,
                (rs, rowNum) -> new ResourceSnapshot(
                        rs.getLong("id"),
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("memo"),
                        rs.getString("status"),
                        rs.getBoolean("is_pinned"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("url"),
                        rs.getString("domain"),
                        rs.getString("file_path"),
                        rs.getString("mime_type")
                ),
                resourceId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }

    private List<String> findTagsByResourceId(long resourceId) {
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

    private void replaceTags(long resourceId, List<String> tags) {
        jdbc.update("DELETE FROM resource_tags WHERE resource_id = ?", resourceId);

        if (tags == null || tags.isEmpty()) {
            return;
        }

        for (String tag : tags) {
            jdbc.update("INSERT INTO tags (name) VALUES (?) ON CONFLICT DO NOTHING", tag);
        }

        List<Long> tagIds = jdbc.query(
                "SELECT id FROM tags WHERE name = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", tags.toArray())),
                (rs, rowNum) -> rs.getLong("id")
        );

        for (Long tagId : tagIds) {
            jdbc.update(
                    """
                    INSERT INTO resource_tags (resource_id, tag_id)
                    VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    resourceId,
                    tagId
            );
        }
    }

    private void publishResourceIndexEvent(ResourceSnapshot snapshot, List<String> tags) {
        if (!"document".equals(snapshot.resourceType()) && !"link".equals(snapshot.resourceType())) {
            throw new IllegalStateException("unsupported resource type: " + snapshot.resourceType());
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("resource_id", snapshot.resourceId());
        payload.put("resource_type", snapshot.resourceType());
        payload.put("domain", "link".equals(snapshot.resourceType()) ? snapshot.domain() : null);
        payload.put("tags", (tags == null ? List.of() : tags));
        payload.put("status", snapshot.status());
        payload.put("is_pinned", snapshot.isPinned());
        payload.put("created_at", snapshot.createdAt() == null ? null : snapshot.createdAt().toString());

        if ("document".equals(snapshot.resourceType())) {
            if (snapshot.filePath() == null || snapshot.filePath().isBlank()) {
                throw new IllegalStateException("document file_path is missing: " + snapshot.resourceId());
            }
            payload.put("document", Map.of(
                    "file_path", snapshot.filePath(),
                    "mime_type", snapshot.mimeType() == null ? "application/octet-stream" : snapshot.mimeType(),
                    "file_name", extractFileName(snapshot.filePath())
            ));
            payload.put("link", null);
        } else {
            Map<String, Object> linkPayload = new HashMap<>();
            linkPayload.put("title", snapshot.title());
            linkPayload.put("memo", snapshot.memo() == null ? "" : snapshot.memo());
            linkPayload.put("tags", (tags == null ? List.of() : tags));
            payload.put("document", null);
            payload.put("link", linkPayload);
        }

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new IllegalStateException("failed to serialize outbox payload", e);
        }

        jdbc.update(
                """
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                VALUES ('resource', ?, 'RESOURCE_INDEX', ?::jsonb)
                """,
                snapshot.resourceId(),
                payloadJson
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

    private static String extractFileName(String filePath) {
        try {
            Path p = Path.of(filePath);
            Path name = p.getFileName();
            if (name != null && !name.toString().isBlank()) {
                return name.toString();
            }
        } catch (Exception ignored) {
        }
        return "original";
    }

    private record DocumentFileRow(
            String filePath,
            String mimeType
    ) {}

    public record DocumentFile(
            Path filePath,
            String mimeType,
            String filename,
            long size
    ) {}

    public record ResourceListResponse(
            List<ResourceListItem> items,
            int page,
            int pageSize,
            long total,
            int totalPages
    ) {}

    public record ResourceListItem(
            long resourceId,
            String type,
            String title,
            String memo,
            String status,
            boolean isPinned,
            OffsetDateTime createdAt,
            String url,
            String domain,
            String mimeType,
            Long fileSize,
            List<String> tags,
            String ingestStatus,
            String ingestStage,
            Integer totalUnits,
            Integer processedUnits,
            String errorMessage,
            OffsetDateTime ingestUpdatedAt
    ) {}

    public record ResourcePatch(
            boolean hasTitle,
            String title,
            boolean hasMemo,
            String memo,
            boolean hasTags,
            List<String> tags,
            boolean hasStatus,
            String status,
            boolean hasIsPinned,
            Boolean isPinned,
            boolean hasUrl,
            String url
    ) {
        public static ResourcePatch empty() {
            return new ResourcePatch(false, null, false, null, false, null, false, null, false, null, false, null);
        }

        public static ResourcePatch statusOnly(String status) {
            return new ResourcePatch(false, null, false, null, false, null, true, status, false, null, false, null);
        }
    }

    public record ResourceUpdateResponse(
            long resourceId,
            boolean updated,
            String resourceType,
            String title,
            String memo,
            List<String> tags,
            String status,
            boolean isPinned,
            String url
    ) {}

    public enum StatusUpdateResult { UPDATED, NOT_FOUND, INVALID_STATUS }
    public record StatusUpdateOutcome(StatusUpdateResult result, String status) {}

    private record ResourceSnapshot(
            long resourceId,
            String resourceType,
            String title,
            String memo,
            String status,
            boolean isPinned,
            OffsetDateTime createdAt,
            String url,
            String domain,
            String filePath,
            String mimeType
    ) {}
}
