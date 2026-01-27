package backend.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class IngestStatusService {

    private final JdbcTemplate jdbc;

    public IngestStatusService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IngestStatus> findByResourceId(long resourceId) {
        List<IngestStatus> list = jdbc.query(
                """
                SELECT resource_id, resource_type, title, status, stage, total_units, processed_units,
                       error_message, created_at, updated_at
                FROM ingest_jobs
                WHERE resource_id = ?
                """,
                (rs, rowNum) -> new IngestStatus(
                        rs.getLong("resource_id"),
                        rs.getString("resource_type"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getString("stage"),
                        (Integer) rs.getObject("total_units"),
                        (Integer) rs.getObject("processed_units"),
                        rs.getString("error_message"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)
                ),
                resourceId
        );
        if (list.isEmpty()) return Optional.empty();
        return Optional.of(list.get(0));
    }

    public List<IngestStatus> listRecent(int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        return jdbc.query(
                """
                SELECT resource_id, resource_type, title, status, stage, total_units, processed_units,
                       error_message, created_at, updated_at
                FROM ingest_jobs
                ORDER BY updated_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new IngestStatus(
                        rs.getLong("resource_id"),
                        rs.getString("resource_type"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getString("stage"),
                        (Integer) rs.getObject("total_units"),
                        (Integer) rs.getObject("processed_units"),
                        rs.getString("error_message"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)
                ),
                size
        );
    }

    public Optional<IngestStatus> updateStatus(long resourceId, String status, String errorMessage) {
        jdbc.update(
                """
                UPDATE ingest_jobs
                SET status = ?, error_message = ?, updated_at = NOW()
                WHERE resource_id = ?
                """,
                status, errorMessage, resourceId
        );
        return findByResourceId(resourceId);
    }

    public Optional<IngestStatus> updateProgress(
            long resourceId,
            String stage,
            Integer totalUnits,
            Integer processedUnits
    ) {
        jdbc.update(
                """
                UPDATE ingest_jobs
                SET stage = ?, total_units = ?, processed_units = ?, updated_at = NOW()
                WHERE resource_id = ?
                """,
                stage, totalUnits, processedUnits, resourceId
        );
        return findByResourceId(resourceId);
    }

    public record IngestStatus(
            long resourceId,
            String resourceType,
            String title,
            String status,
            String stage,
            Integer totalUnits,
            Integer processedUnits,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}
}
