package backend.search;

import backend.search.internal.ResourceMeta;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class ResourceMetaRepository {

    private final JdbcTemplate jdbc;

    public ResourceMetaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, ResourceMeta> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();

        // Postgres에서 tags를 array_agg로 묶어서 한 row로 가져오기
        // links/documents는 type에 따라 left join으로 같이 가져옴
        String sql = """
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

              d.file_path               AS file_path,
              d.mime_type               AS mime_type,
              d.file_size               AS file_size,
              d.sha256                  AS sha256,

              COALESCE(array_agg(t.name) FILTER (WHERE t.name IS NOT NULL), ARRAY[]::text[]) AS tags
            FROM resources r
              LEFT JOIN links l ON l.resource_id = r.id
              LEFT JOIN documents d ON d.resource_id = r.id
              LEFT JOIN resource_tags rt ON rt.resource_id = r.id
              LEFT JOIN tags t ON t.id = rt.tag_id
            WHERE r.id = ANY (?)
            GROUP BY
              r.id, r.type, r.title, r.memo, r.status, r.is_pinned, r.created_at,
              l.url, l.domain,
              d.file_path, d.mime_type, d.file_size, d.sha256
            """;

        return jdbc.query(sql, ps -> {
            Array arr = ps.getConnection().createArrayOf("int8", ids.toArray());
            ps.setArray(1, arr);
        }, (ResultSet rs) -> {
            Map<Long, ResourceMeta> map = new HashMap<>();
            while (rs.next()) {
                long resourceId = rs.getLong("resource_id");

                List<String> tags = readTextArray(rs, "tags");

                ResourceMeta meta = new ResourceMeta(
                        resourceId,
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("memo"),
                        rs.getString("status"),
                        rs.getBoolean("is_pinned"),
                        rs.getObject("created_at", OffsetDateTime.class),

                        rs.getString("url"),
                        rs.getString("domain"),

                        rs.getString("file_path"),
                        rs.getString("mime_type"),
                        (rs.getObject("file_size") == null ? null : rs.getLong("file_size")),
                        rs.getString("sha256"),

                        tags
                );
                map.put(resourceId, meta);
            }
            return map;
        });
    }

    private static List<String> readTextArray(ResultSet rs, String col) throws SQLException {
        Array arr = rs.getArray(col);
        if (arr == null) return List.of();
        String[] v = (String[]) arr.getArray();
        if (v == null) return List.of();
        // 중복 제거 + 안정적 순서
        return Arrays.stream(v).filter(Objects::nonNull).distinct().toList();
    }
}