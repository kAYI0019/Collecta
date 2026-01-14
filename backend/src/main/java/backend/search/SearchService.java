package backend.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import backend.search.dto.GroupedSearchResultDto;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final String INDEX = "collecta_chunks";

    private final ElasticsearchClient es;

    public SearchService(ElasticsearchClient es) {
        this.es = es;
    }

    public List<GroupedSearchResultDto> searchGrouped(String q) throws Exception {
        if (q == null || q.isBlank()) return List.of();

        // 1) ES에서 chunk hits를 가져오기
        int fetchSize = 200;
        int topN = 20;

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .size(fetchSize)
                .query(qb -> qb
                        .match(m -> m
                                .field("chunk_text")
                                .query(q)
                        )
                )
                .highlight(h -> h
                        .fields("chunk_text", f -> f)
                )
        );

        SearchResponse<Map> response = es.search(request, Map.class);
        List<Hit<Map>> hits = response.hits().hits();

        // 2) resource_id로 그룹핑 (HashMap 누적)
        Map<String, Acc> byResource = new HashMap<>();

        for (Hit<Map> hit : hits) {
            Map<String, Object> src = hit.source();
            if (src == null) continue;

            String resourceId = (String) src.get("resource_id");
            if (resourceId == null) continue;

            String resourceType = (String) src.get("resource_type");
            String domain = (String) src.get("domain");
            List<String> tags = safeStringList(src.get("tags"));
            Integer pageIndex = safeInteger(src.get("page_index"));

            double score = hit.score() != null ? hit.score() : 0.0;

            String snippet = extractSnippet(hit, src);

            Acc acc = byResource.computeIfAbsent(resourceId, k -> new Acc(resourceId));
            acc.matchCount++;

            // 대표 chunk 선택: bestScore가 더 크면 갱신
            if (score > acc.bestScore) {
                acc.bestScore = score;
                acc.resourceType = resourceType;
                acc.domain = domain;
                acc.tags = tags;
                acc.bestSnippet = snippet;
                acc.bestPageIndex = pageIndex;
            }
        }

        // 3) 정렬 + topN 자르기
        return byResource.values().stream()
                .sorted(Comparator.comparingDouble((Acc a) -> a.bestScore).reversed())
                .limit(topN)
                .map(Acc::toDto)
                .collect(Collectors.toList());
    }

    // ---------------------
    // Helpers
    // ---------------------

    private static String extractSnippet(Hit<Map> hit, Map<String, Object> src) {
        // highlight가 있으면 그걸 우선 사용
        if (hit.highlight() != null && hit.highlight().get("chunk_text") != null) {
            List<String> hl = hit.highlight().get("chunk_text");
            if (hl != null && !hl.isEmpty()) {
                return String.join(" ", hl);
            }
        }

        // 없으면 chunk_text 앞부분으로 fallback
        Object ct = src.get("chunk_text");
        if (ct instanceof String s) {
            String t = s.strip();
            if (t.length() <= 220) return t;
            return t.substring(0, 220) + "…";
        }

        return null;
    }

    private static List<String> safeStringList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s) out.add(s);
            }
            return out;
        }
        return List.of();
    }

    private static Integer safeInteger(Object v) {
        if (v == null) return null;
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        return null;
    }

    private static class Acc {
        final String resourceId;
        int matchCount = 0;

        double bestScore = -1.0;
        String resourceType;
        String domain;
        List<String> tags = List.of();
        String bestSnippet;
        Integer bestPageIndex;

        Acc(String resourceId) {
            this.resourceId = resourceId;
        }

        GroupedSearchResultDto toDto() {
            return new GroupedSearchResultDto(
                    resourceId,
                    resourceType,
                    domain,
                    tags,
                    matchCount,
                    bestScore,
                    bestSnippet,
                    bestPageIndex
            );
        }
    }
}
