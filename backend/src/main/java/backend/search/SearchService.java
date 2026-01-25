package backend.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import backend.search.dto.GroupedSearchResultDto;
import backend.search.dto.PagedResponse;
import backend.search.dto.SearchResourceItemDto;
import backend.search.internal.ResourceMeta;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final String INDEX = "collecta-chunks";

    private final ElasticsearchClient es;
    private final ResourceMetaRepository resourceMetaRepository;
    private final EmbeddingClient embeddingClient;

    public SearchService(
            ElasticsearchClient es,
            ResourceMetaRepository resourceMetaRepository,
            EmbeddingClient embeddingClient
    ) {
        this.es = es;
        this.resourceMetaRepository = resourceMetaRepository;
        this.embeddingClient = embeddingClient;
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

        Query query = Query.of(qb -> qb.bool(b -> {
            b.must(m -> m.match(mm -> mm.field("chunk_text").query(q)));
            applyFilters(b, resourceType, domain, status, isPinned, tags);
            return b;
        }));

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .size(fetchSize)
                .query(query)
                .highlight(h -> h.fields("chunk_text", f -> f))
        );

        SearchResponse<Map> response = es.search(request, Map.class);
        List<Hit<Map>> hits = response.hits().hits();

        return groupAndPage(hits, page, pageSize, sort);
    }

    public PagedResponse<GroupedSearchResultDto> searchGroupedPagedVector(
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

        List<Double> queryVector = embeddingClient.embedOne(q);

        page = clampNonNeg(page);
        pageSize = clampPageSize(pageSize);

        int fetchSize = Math.max(300, pageSize * 30);

        Query query = Query.of(qb -> qb.bool(b -> {
            b.must(m -> m.scriptScore(ss -> ss
                    .query(qm -> qm.matchAll(ma -> ma))
                    .script(sc -> sc
                            .source("cosineSimilarity(params.query_vector, 'embedding') + 1.0")
                            .params("query_vector", JsonData.of(queryVector))
                    )
            ));
            applyFilters(b, resourceType, domain, status, isPinned, tags);
            return b;
        }));

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .size(fetchSize)
                .query(query)
                .highlight(h -> h.fields("chunk_text", f -> f))
        );

        SearchResponse<Map> response = es.search(request, Map.class);
        List<Hit<Map>> hits = response.hits().hits();

        return groupAndPage(hits, page, pageSize, sort);
    }

    public PagedResponse<GroupedSearchResultDto> searchGroupedPagedHybrid(
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

        List<Double> queryVector = embeddingClient.embedOne(q);

        page = clampNonNeg(page);
        pageSize = clampPageSize(pageSize);

        int fetchSize = Math.max(300, pageSize * 30);

        Query keywordQuery = Query.of(qb -> qb.bool(b -> {
            b.must(m -> m.match(mm -> mm.field("chunk_text").query(q)));
            applyFilters(b, resourceType, domain, status, isPinned, tags);
            return b;
        }));

        Query hybridQuery = Query.of(qb -> qb.functionScore(fs -> fs
                .query(keywordQuery)
                .functions(f -> f.scriptScore(ss -> ss
                                .script(sc -> sc
                                        .source("cosineSimilarity(params.query_vector, 'embedding') + 1.0")
                                        .params("query_vector", JsonData.of(queryVector))
                                )
                        )
                        .weight(0.4)
                )
                .scoreMode(co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode.Sum)
                .boostMode(co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode.Sum)
        ));

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .size(fetchSize)
                .query(hybridQuery)
                .highlight(h -> h.fields("chunk_text", f -> f))
        );

        SearchResponse<Map> response = es.search(request, Map.class);
        List<Hit<Map>> hits = response.hits().hits();

        return groupAndPage(hits, page, pageSize, sort);
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

    private static void applyFilters(
            BoolQuery.Builder b,
            String resourceType,
            String domain,
            String status,
            Boolean isPinned,
            List<String> tags
    ) {
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
            b.filter(f -> f.terms(t -> t.field("tags").terms(tt -> tt.value(
                    tags.stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()
            ))));
        }
    }

    private PagedResponse<GroupedSearchResultDto> groupAndPage(
            List<Hit<Map>> hits,
            int page,
            int pageSize,
            String sort
    ) {
        Map<String, Acc> byResource = new HashMap<>();

        for (Hit<Map> hit : hits) {
            Map<String, Object> src = hit.source();
            if (src == null) continue;

            Object rid = src.get("resource_id");
            if (rid == null) continue;
            String resourceId = String.valueOf(rid);

            String rType = (String) src.get("resource_type");
            String rDomain = (String) src.get("domain");
            List<String> rTags = safeStringList(src.get("tags"));
            Integer pageIndex = safeInteger(src.get("page_index"));

            double score = hit.score() != null ? hit.score() : 0.0;
            String snippet = extractSnippet(hit, src);

            Acc acc = byResource.computeIfAbsent(resourceId, Acc::new);
            acc.matchCount++;

            if (score > acc.bestScore) {
                acc.bestScore = score;
                acc.resourceType = rType;
                acc.domain = rDomain;
                acc.tags = rTags;
                acc.bestSnippet = snippet;
                acc.bestPageIndex = pageIndex;

                acc.isPinned = safeBoolean(src.get("is_pinned"));
                acc.createdAt = safeString(src.get("created_at"));
            }
        }

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

    public PagedResponse<SearchResourceItemDto> searchResourceCardsSemantic(
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
        PagedResponse<GroupedSearchResultDto> grouped =
                searchGroupedPagedVector(q, resourceType, domain, status, isPinned, tags, page, pageSize, sort);

        List<Long> ids = grouped.items().stream()
                .map(it -> Long.parseLong(it.resourceId()))
                .toList();

        Map<Long, ResourceMeta> metaMap = resourceMetaRepository.findByIds(ids);

        List<SearchResourceItemDto> items = grouped.items().stream().map(g -> {
            long id = Long.parseLong(g.resourceId());
            ResourceMeta m = metaMap.get(id);

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
                    m.tags(),
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

    public PagedResponse<SearchResourceItemDto> searchResourceCardsHybrid(
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
        PagedResponse<GroupedSearchResultDto> grouped =
                searchGroupedPagedHybrid(q, resourceType, domain, status, isPinned, tags, page, pageSize, sort);

        List<Long> ids = grouped.items().stream()
                .map(it -> Long.parseLong(it.resourceId()))
                .toList();

        Map<Long, ResourceMeta> metaMap = resourceMetaRepository.findByIds(ids);

        List<SearchResourceItemDto> items = grouped.items().stream().map(g -> {
            long id = Long.parseLong(g.resourceId());
            ResourceMeta m = metaMap.get(id);

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
                    m.tags(),
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
