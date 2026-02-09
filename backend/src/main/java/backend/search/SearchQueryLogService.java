package backend.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SearchQueryLogService {

    private final JdbcTemplate jdbc;

    public SearchQueryLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(
            String queryText,
            String mode,
            String resourceType,
            String domain,
            String status,
            Boolean isPinned,
            List<String> tags,
            String sort,
            int page,
            int pageSize,
            long totalResults,
            long latencyMs
    ) {
        String tagText = (tags == null || tags.isEmpty()) ? null : String.join(",", tags);
        jdbc.update(
                """
                INSERT INTO search_query_logs (
                  query_text, mode, resource_type, domain, status, is_pinned, tags, sort,
                  page, page_size, total_results, latency_ms
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                queryText, mode, resourceType, domain, status, isPinned, tagText, sort,
                page, pageSize, totalResults, latencyMs
        );
    }
}

