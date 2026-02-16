package backend.resource;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldSort;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class ResourceContentService {

    private static final String INDEX = "collecta-chunks";
    private static final int MAX_CHUNKS = 10_000;

    private final JdbcTemplate jdbc;
    private final ElasticsearchClient es;

    public ResourceContentService(
            JdbcTemplate jdbc,
            ElasticsearchClient es
    ) {
        this.jdbc = jdbc;
        this.es = es;
    }

    public Optional<ResourceContentResponse> findResourceContent(long resourceId, String q) throws Exception {
        Optional<ResourceMetaLite> metaOpt = findResourceMeta(resourceId);
        if (metaOpt.isEmpty()) {
            return Optional.empty();
        }

        String normalizedQuery = normalizeQuery(q);
        List<ResourceChunkContent> chunks = fetchChunks(resourceId, normalizedQuery);

        return Optional.of(new ResourceContentResponse(
                resourceId,
                metaOpt.get().resourceType(),
                metaOpt.get().title(),
                normalizedQuery,
                chunks.size(),
                chunks
        ));
    }

    private Optional<ResourceMetaLite> findResourceMeta(long resourceId) {
        List<ResourceMetaLite> rows = jdbc.query(
                """
                SELECT id, type, title
                FROM resources
                WHERE id = ?
                """,
                (rs, rowNum) -> new ResourceMetaLite(
                        rs.getLong("id"),
                        rs.getString("type"),
                        rs.getString("title")
                ),
                resourceId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<ResourceChunkContent> fetchChunks(long resourceId, String query) throws Exception {
        Query esQuery = Query.of(q -> q.term(t -> t.field("resource_id").value(Long.toString(resourceId))));

        SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
                .index(INDEX)
                .size(MAX_CHUNKS)
                .query(esQuery)
                .sort(s -> s.field(FieldSort.of(f -> f
                        .field("page_index")
                        .order(SortOrder.Asc)
                        .missing("_last")
                )))
                .sort(s -> s.field(FieldSort.of(f -> f
                        .field("position")
                        .order(SortOrder.Asc)
                        .missing("_last")
                )));

        if (query != null) {
            requestBuilder.highlight(h -> h.fields("chunk_text", f -> f));
        }

        SearchResponse<Map> response;
        try {
            response = es.search(requestBuilder.build(), Map.class);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("index_not_found_exception")) {
                return List.of();
            }
            throw e;
        }
        List<ResourceChunkContent> out = new ArrayList<>();

        for (Hit<Map> hit : response.hits().hits()) {
            Map<String, Object> source = hit.source();
            if (source == null) continue;

            String text = safeString(source.get("chunk_text"));
            if (text == null || text.isBlank()) continue;

            Integer pageIndex = safeInteger(source.get("page_index"));
            Integer position = safeInteger(source.get("position"));

            String highlightedText = extractHighlight(hit);
            boolean matched = query != null && (
                    highlightedText != null ||
                    text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))
            );

            out.add(new ResourceChunkContent(
                    pageIndex,
                    position,
                    text,
                    highlightedText,
                    matched
            ));
        }

        return out;
    }

    private static String extractHighlight(Hit<Map> hit) {
        if (hit.highlight() == null) return null;
        List<String> chunkHighlights = hit.highlight().get("chunk_text");
        if (chunkHighlights == null || chunkHighlights.isEmpty()) return null;
        return String.join(" ", chunkHighlights);
    }

    private static String normalizeQuery(String q) {
        if (q == null) return null;
        String trimmed = q.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed;
    }

    private static String safeString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static Integer safeInteger(Object value) {
        if (value instanceof Integer i) return i;
        if (value instanceof Number n) return n.intValue();
        return null;
    }

    private record ResourceMetaLite(
            long resourceId,
            String resourceType,
            String title
    ) {}

    public record ResourceContentResponse(
            long resourceId,
            String resourceType,
            String title,
            String query,
            int chunkCount,
            List<ResourceChunkContent> chunks
    ) {}

    public record ResourceChunkContent(
            Integer pageIndex,
            Integer position,
            String text,
            String highlightedText,
            boolean matched
    ) {}
}
