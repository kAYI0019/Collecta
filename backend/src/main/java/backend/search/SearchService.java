package backend.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import backend.search.dto.GroupedSearchResultDto;
import backend.search.dto.PagedResponse;
import backend.search.dto.SearchResourceItemDto;
import backend.search.internal.ResourceMeta;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final String INDEX = "collecta_chunks";

    private final ElasticsearchClient es;
    private final ResourceMetaRepository resourceMetaRepository;

    public SearchService(ElasticsearchClient es, ResourceMetaRepository resourceMetaRepository) {
        this.es = es;
        this.resourceMetaRepository = resourceMetaRepository;
    }

    public PagedResponse<GroupedSearchResultDto> searchGroupedPaged(
            String q,
            String resourceType,
            String domain,
            String status,
            Boolean isPinned,
            List<String> tags,
            int page,
            int pageSize,
            String sort
    ) throws Exception {

        if (q == null || q.isBlank()) {
            return new PagedResponse<>(List.of(), clampNonNeg(page), clampPageSize(pageSize), 0, 0);
        }

        page = clampNonNeg(page);
        pageSize = clampPageSize(pageSize);

        int fetchSize = Math.max(300, pageSize * 30); // 필터/그룹핑 고려해 넉넉히

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .size(fetchSize)
                .query(qb -> qb.bool(b -> {
                    // must: query
                    b.must(m -> m.match(mm -> mm.field("chunk_text").query(q)));

                    // filters: 존재할 때만 추가
                    if (resourceType != null && !resourceType.isBlank()) {
                        b.filter(f -> f.term(t -> t.field("resource_type").value(resourceType)));
                    }
                    if (domain != null && !domain.isBlank()) {
                        b.filter(f -> f.term(t -> t.field("domain").value(domain)));
                    }
                    if (status != null && !status.isBlank()) {
                        b.filter(f -> f.term(t -> t.field("status").value(status)));
                    }
                    if (isPinned != null) {
                        b.filter(f -> f.term(t -> t.field("is_pinned").value(isPinned)));
                    }
                    if (tags != null && !tags.isEmpty()) {
                        // tags 중 하나라도 포함(OR). 전부 포함(AND)로 하고 싶으면 loop로 filter term을 여러개 넣으면 됨.
                        b.filter(f -> f.terms(t -> t.field("tags").terms(tt -> tt.value(
                                tags.stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()
                        ))));
                    }

                    return b;
                }))
                .highlight(h -> h.fields("chunk_text", f -> f))
        );

        SearchResponse<Map> response = es.search(request, Map.class);
        List<Hit<Map>> hits = response.hits().hits();

        // 그룹핑
        Map<String, Acc> byResource = new HashMap<>();

        for (Hit<Map> hit : hits) {
            Map<String, Object> src = hit.source();
            if (src == null) continue;

            String resourceId = (String) src.get("resource_id");
            if (resourceId == null) continue;

            String rType = (String) src.get("resource_type");
            String rDomain = (String) src.get("domain");
            List<String> rTags = safeStringList(src.get("tags"));
            Integer pageIndex = safeInteger(src.get("page_index"));

            double score = hit.score() != null ? hit.score() : 0.0;
            String snippet = extractSnippet(hit, src);

            Acc acc = byResource.computeIfAbsent(resourceId, Acc::new);
            acc.matchCount++;

            // 대표 chunk 갱신
            if (score > acc.bestScore) {
                acc.bestScore = score;
                acc.resourceType = rType;
                acc.domain = rDomain;
                acc.tags = rTags;
                acc.bestSnippet = snippet;
                acc.bestPageIndex = pageIndex;

                // created_at, is_pinned를 응답/정렬에 쓰고 싶으면 ES 문서에서 꺼내서 acc에 저장해도 됨
                acc.isPinned = safeBoolean(src.get("is_pinned"));
                acc.createdAt = safeString(src.get("created_at"));
            }
        }

        // 정렬 (그룹핑 결과 기준)
        List<Acc> accList = new ArrayList<>(byResource.values());

        switch ((sort == null ? "relevance" : sort).toLowerCase()) {
            case "pinned" -> accList.sort(
                    Comparator.comparing((Acc a) -> a.isPinned == null ? false : a.isPinned).reversed()
                            .thenComparingDouble(a -> a.bestScore).reversed()
            );
            case "newest" -> accList.sort(
                    Comparator.comparing((Acc a) -> a.createdAt == null ? "" : a.createdAt).reversed()
                            .thenComparingDouble(a -> a.bestScore).reversed()
            );
            default -> accList.sort(Comparator.comparingDouble((Acc a) -> a.bestScore).reversed());
        }

        List<GroupedSearchResultDto> all = accList.stream()
                .map(Acc::toDto)
                .collect(Collectors.toList());

        // 페이징
        long total = all.size();
        int totalPages = (int) Math.ceil(total / (double) pageSize);

        int from = page * pageSize;
        if (from >= total) {
            return new PagedResponse<>(List.of(), page, pageSize, total, totalPages);
        }
        int to = Math.min(from + pageSize, (int) total);
        List<GroupedSearchResultDto> items = all.subList(from, to);

        return new PagedResponse<>(items, page, pageSize, total, totalPages);
    }

    // -------- Helpers --------
    private static int clampNonNeg(int v) { return Math.max(0, v); }
    private static int clampPageSize(int v) { if (v <= 0) return 20; return Math.min(v, 100); }

    private static String extractSnippet(Hit<Map> hit, Map<String, Object> src) {
        if (hit.highlight() != null && hit.highlight().get("chunk_text") != null) {
            List<String> hl = hit.highlight().get("chunk_text");
            if (hl != null && !hl.isEmpty()) return String.join(" ", hl);
        }
        Object ct = src.get("chunk_text");
        if (ct instanceof String s) {
            String t = s.strip();
            if (t.length() <= 220) return t;
            return t.substring(0, 220) + "…";
        }
        return null;
    }

    private static List<String> safeStringList(Object v) {
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) if (o instanceof String s) out.add(s);
            return out;
        }
        return List.of();
    }
    private static Integer safeInteger(Object v) {
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        return null;
    }
    private static Boolean safeBoolean(Object v) {
        if (v instanceof Boolean b) return b;
        return null;
    }
    private static String safeString(Object v) {
        if (v instanceof String s) return s;
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

        // sorting 보조 필드
        Boolean isPinned;
        String createdAt;

        Acc(String resourceId) { this.resourceId = resourceId; }

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

    public PagedResponse<SearchResourceItemDto> searchResourceCards(
            String q,
            String resourceType,
            String domain,
            String status,
            Boolean isPinned,
            List<String> tags,
            int page,
            int pageSize,
            String sort
    ) throws Exception {
        // 1) ES에서 그룹핑 + 페이징 메타까지 얻기 (지금 네 메서드 그대로 활용)
        PagedResponse<GroupedSearchResultDto> grouped =
                searchGroupedPaged(q, resourceType, domain, status, isPinned, tags, page, pageSize, sort);

        // 2) 이번 페이지에 나온 resourceId들만 DB에서 메타 조회
        List<Long> ids = grouped.items().stream()
                .map(it -> Long.parseLong(it.resourceId()))
                .toList();

        Map<Long, ResourceMeta> metaMap = resourceMetaRepository.findByIds(ids);

        // 3) ES 순서대로 합치기 (순서 유지 매우 중요)
        List<SearchResourceItemDto> items = grouped.items().stream().map(g -> {
            long id = Long.parseLong(g.resourceId());
            ResourceMeta m = metaMap.get(id);

            // 혹시 DB에 없으면(삭제됐는데 ES 남아있는 경우) 최소한의 fallback
            if (m == null) {
                return new SearchResourceItemDto(
                        id, g.resourceType(),
                        null, null, null, false, null,
                        null, g.domain(),
                        null, null, null, null,
                        g.tags(),
                        g.matchCount(), g.bestScore(), g.bestSnippet(), g.bestPageIndex()
                );
            }

            return new SearchResourceItemDto(
                    m.resourceId(), m.type(),
                    m.title(), m.memo(), m.status(), m.isPinned(), m.createdAt(),
                    m.url(), m.domain(),
                    m.filePath(), m.mimeType(), m.fileSize(), m.sha256(),
                    m.tags(), // tags는 DB 기준(정합성)
                    g.matchCount(), g.bestScore(), g.bestSnippet(), g.bestPageIndex()
            );
        }).toList();

        return new PagedResponse<>(
                items,
                grouped.page(),
                grouped.pageSize(),
                grouped.total(),
                grouped.totalPages()
        );
    }
}
