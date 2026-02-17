package backend.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import backend.search.dto.GroupedSearchResultDto;
import backend.search.dto.PagedResponse;
import backend.search.dto.SearchDebugMetaDto;
import backend.search.dto.SearchResourceItemDto;
import backend.search.dto.SearchResponseDto;
import backend.search.internal.ResourceMeta;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final String INDEX = "collecta-chunks";

    private final ElasticsearchClient es;
    private final ResourceMetaRepository resourceMetaRepository;
    private final EmbeddingClient embeddingClient;
    private final SearchQueryLogService searchQueryLogService;
    private final double hybridVectorWeight;

    public SearchService(
            ElasticsearchClient es,
            ResourceMetaRepository resourceMetaRepository,
            EmbeddingClient embeddingClient,
            SearchQueryLogService searchQueryLogService,
            @Value("${search.hybrid.vector-weight:0.4}") double hybridVectorWeight
    ) {
        this.es = es;
        this.resourceMetaRepository = resourceMetaRepository;
        this.embeddingClient = embeddingClient;
        this.searchQueryLogService = searchQueryLogService;
        this.hybridVectorWeight = hybridVectorWeight;
    }

    public SearchResponseDto searchResourceCards(
            String q,
            String resourceType,
            String domain,
            List<String> statuses,
            Boolean isPinned,
            List<String> tags,
            int page,
            int pageSize,
            String sort,
            String mode,
            boolean debug,
            boolean logQuery
    ) throws Exception {
        long startedAt = System.currentTimeMillis();
        String effectiveMode = normalizeMode(mode);
        int safePage = clampNonNeg(page);
        int safePageSize = clampPageSize(pageSize);
        List<String> safeStatuses = sanitizeStatuses(statuses);
        String statusForLog = safeStatuses.isEmpty() ? null : String.join(",", safeStatuses);

        if (q == null || q.isBlank()) {
            SearchResponseDto empty = new SearchResponseDto(
                    List.of(),
                    safePage,
                    safePageSize,
                    0,
                    0,
                    new SearchDebugMetaDto(effectiveMode, debug, System.currentTimeMillis() - startedAt, null, 0L)
            );
            if (logQuery) {
                searchQueryLogService.log(
                        q,
                        effectiveMode,
                        resourceType,
                        domain,
                        statusForLog,
                        isPinned,
                        tags,
                        sort,
                        safePage,
                        safePageSize,
                        0,
                        empty.debug().totalMs()
                );
            }
            return empty;
        }

        Long embedMs = null;
        List<Double> queryVector = null;
        if (effectiveMode.equals("semantic") || effectiveMode.equals("hybrid")) {
            long embedStart = System.currentTimeMillis();
            queryVector = embeddingClient.embedOne(q);
            embedMs = System.currentTimeMillis() - embedStart;
        }

        SearchRunResult runResult;
        switch (effectiveMode) {
            case "semantic" -> runResult = runSemantic(
                    q, queryVector, resourceType, domain, safeStatuses, isPinned, tags, safePage, safePageSize, sort
            );
            case "hybrid" -> runResult = runHybrid(
                    q, queryVector, resourceType, domain, safeStatuses, isPinned, tags, safePage, safePageSize, sort
            );
            default -> runResult = runKeyword(
                    q, resourceType, domain, safeStatuses, isPinned, tags, safePage, safePageSize, sort
            );
        }

        PagedResponse<GroupedSearchResultDto> grouped = runResult.grouped();

        List<Long> ids = grouped.items().stream()
                .map(it -> Long.parseLong(it.resourceId()))
                .toList();

        Map<Long, ResourceMeta> metaMap = resourceMetaRepository.findByIds(ids);
        Map<String, ScoreParts> scoreMap = debug
                ? buildDebugScores(effectiveMode, q, queryVector, grouped.items())
                : Map.of();

        List<SearchResourceItemDto> items = grouped.items().stream().map(g -> {
            long id = Long.parseLong(g.resourceId());
            ResourceMeta m = metaMap.get(id);

            ScoreParts scoreParts = scoreMap.get(g.resourceId());
            Double keywordScore = scoreParts == null ? null : scoreParts.keywordScore();
            Double vectorScore = scoreParts == null ? null : scoreParts.vectorScore();
            Double finalScore = scoreParts == null ? null : scoreParts.finalScore();

            if (m == null) {
                return new SearchResourceItemDto(
                        id,
                        g.resourceType(),
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        g.domain(),
                        null,
                        null,
                        null,
                        null,
                        g.tags(),
                        g.matchCount(),
                        g.bestScore(),
                        g.bestSnippet(),
                        g.bestPageIndex(),
                        keywordScore,
                        vectorScore,
                        finalScore
                );
            }

            return new SearchResourceItemDto(
                    m.resourceId(),
                    m.type(),
                    m.title(),
                    m.memo(),
                    m.status(),
                    m.isPinned(),
                    m.createdAt(),
                    m.url(),
                    m.domain(),
                    m.filePath(),
                    m.mimeType(),
                    m.fileSize(),
                    m.sha256(),
                    m.tags(),
                    g.matchCount(),
                    g.bestScore(),
                    g.bestSnippet(),
                    g.bestPageIndex(),
                    keywordScore,
                    vectorScore,
                    finalScore
            );
        }).toList();

        long totalMs = System.currentTimeMillis() - startedAt;

        SearchResponseDto response = new SearchResponseDto(
                items,
                grouped.page(),
                grouped.pageSize(),
                grouped.total(),
                grouped.totalPages(),
                new SearchDebugMetaDto(
                        effectiveMode,
                        debug,
                        totalMs,
                        embedMs,
                        runResult.esMs()
                )
        );

        if (logQuery) {
            searchQueryLogService.log(
                    q,
                    effectiveMode,
                    resourceType,
                    domain,
                    statusForLog,
                    isPinned,
                    tags,
                    sort,
                    safePage,
                    safePageSize,
                    response.total(),
                    totalMs
            );
        }

        return response;
    }

    private SearchRunResult runKeyword(
            String q,
            String resourceType,
            String domain,
            List<String> statuses,
            Boolean isPinned,
            List<String> tags,
            int page,
            int pageSize,
            String sort
    ) throws Exception {
        int fetchSize = Math.max(300, pageSize * 30);

        Query query = Query.of(qb -> qb.bool(b -> {
            b.must(m -> m.match(mm -> mm.field("chunk_text").query(q)));
            applyFilters(b, resourceType, domain, statuses, isPinned, tags);
            return b;
        }));

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .size(fetchSize)
                .query(query)
                .highlight(h -> h.fields("chunk_text", f -> f))
        );

        long esStart = System.currentTimeMillis();
        SearchResponse<Map> response = es.search(request, Map.class);
        long esMs = System.currentTimeMillis() - esStart;

        PagedResponse<GroupedSearchResultDto> grouped = groupAndPage(response.hits().hits(), page, pageSize, sort);
        return new SearchRunResult(grouped, esMs);
    }

    private SearchRunResult runSemantic(
            String q,
            List<Double> queryVector,
            String resourceType,
            String domain,
            List<String> statuses,
            Boolean isPinned,
            List<String> tags,
            int page,
            int pageSize,
            String sort
    ) throws Exception {
        int fetchSize = Math.max(300, pageSize * 30);

        Query query = Query.of(qb -> qb.bool(b -> {
            b.must(m -> m.scriptScore(ss -> ss
                    .query(qm -> qm.matchAll(ma -> ma))
                    .script(sc -> sc
                            .source("cosineSimilarity(params.query_vector, 'embedding') + 1.0")
                            .params("query_vector", JsonData.of(queryVector))
                    )
            ));
            applyFilters(b, resourceType, domain, statuses, isPinned, tags);
            return b;
        }));

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .size(fetchSize)
                .query(query)
                .highlight(h -> h.fields("chunk_text", f -> f))
        );

        long esStart = System.currentTimeMillis();
        SearchResponse<Map> response = es.search(request, Map.class);
        long esMs = System.currentTimeMillis() - esStart;

        PagedResponse<GroupedSearchResultDto> grouped = groupAndPage(response.hits().hits(), page, pageSize, sort);
        return new SearchRunResult(grouped, esMs);
    }

    private SearchRunResult runHybrid(
            String q,
            List<Double> queryVector,
            String resourceType,
            String domain,
            List<String> statuses,
            Boolean isPinned,
            List<String> tags,
            int page,
            int pageSize,
            String sort
    ) throws Exception {
        int fetchSize = Math.max(300, pageSize * 30);

        Query keywordQuery = Query.of(qb -> qb.bool(b -> {
            b.must(m -> m.match(mm -> mm.field("chunk_text").query(q)));
            applyFilters(b, resourceType, domain, statuses, isPinned, tags);
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
                        .weight(hybridVectorWeight)
                )
                .scoreMode(FunctionScoreMode.Sum)
                .boostMode(FunctionBoostMode.Sum)
        ));

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .size(fetchSize)
                .query(hybridQuery)
                .highlight(h -> h.fields("chunk_text", f -> f))
        );

        long esStart = System.currentTimeMillis();
        SearchResponse<Map> response = es.search(request, Map.class);
        long esMs = System.currentTimeMillis() - esStart;

        PagedResponse<GroupedSearchResultDto> grouped = groupAndPage(response.hits().hits(), page, pageSize, sort);
        return new SearchRunResult(grouped, esMs);
    }

    private Map<String, ScoreParts> buildDebugScores(
            String mode,
            String q,
            List<Double> queryVector,
            List<GroupedSearchResultDto> groupedItems
    ) throws Exception {
        Map<String, ScoreParts> out = new HashMap<>();
        for (GroupedSearchResultDto g : groupedItems) {
            out.put(g.resourceId(), new ScoreParts(null, null, g.bestScore()));
        }

        if (groupedItems.isEmpty()) {
            return out;
        }

        List<String> ids = groupedItems.stream().map(GroupedSearchResultDto::resourceId).toList();

        if (mode.equals("keyword")) {
            for (GroupedSearchResultDto g : groupedItems) {
                out.put(g.resourceId(), new ScoreParts(g.bestScore(), null, g.bestScore()));
            }
            return out;
        }

        if (mode.equals("semantic")) {
            for (GroupedSearchResultDto g : groupedItems) {
                out.put(g.resourceId(), new ScoreParts(null, g.bestScore(), g.bestScore()));
            }
            return out;
        }

        Map<String, Double> keywordMap = fetchKeywordScoresByResource(q, ids);
        Map<String, Double> vectorMap = fetchVectorScoresByResource(queryVector, ids);

        for (GroupedSearchResultDto g : groupedItems) {
            out.put(
                    g.resourceId(),
                    new ScoreParts(
                            keywordMap.get(g.resourceId()),
                            vectorMap.get(g.resourceId()),
                            g.bestScore()
                    )
            );
        }

        return out;
    }

    private Map<String, Double> fetchKeywordScoresByResource(String q, List<String> resourceIds) throws Exception {
        int fetchSize = Math.min(5000, Math.max(500, resourceIds.size() * 80));

        Query query = Query.of(qb -> qb.bool(b -> b
                .must(m -> m.match(mm -> mm.field("chunk_text").query(q)))
                .filter(f -> f.terms(t -> t
                        .field("resource_id")
                        .terms(tt -> tt.value(resourceIds.stream().map(FieldValue::of).toList()))
                ))
        ));

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .size(fetchSize)
                .query(query)
        );

        SearchResponse<Map> response = es.search(request, Map.class);
        Map<String, Double> out = new HashMap<>();

        for (Hit<Map> hit : response.hits().hits()) {
            Map<String, Object> src = hit.source();
            if (src == null) continue;
            Object rid = src.get("resource_id");
            if (rid == null) continue;

            String id = String.valueOf(rid);
            double score = Optional.ofNullable(hit.score()).orElse(0.0);
            out.merge(id, score, Math::max);
        }

        return out;
    }

    private Map<String, Double> fetchVectorScoresByResource(List<Double> queryVector, List<String> resourceIds) throws Exception {
        int fetchSize = Math.min(5000, Math.max(500, resourceIds.size() * 80));

        Query query = Query.of(qb -> qb.bool(b -> b
                .must(m -> m.scriptScore(ss -> ss
                        .query(qm -> qm.matchAll(ma -> ma))
                        .script(sc -> sc
                                .source("cosineSimilarity(params.query_vector, 'embedding') + 1.0")
                                .params("query_vector", JsonData.of(queryVector))
                        )
                ))
                .filter(f -> f.terms(t -> t
                        .field("resource_id")
                        .terms(tt -> tt.value(resourceIds.stream().map(FieldValue::of).toList()))
                ))
        ));

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .size(fetchSize)
                .query(query)
        );

        SearchResponse<Map> response = es.search(request, Map.class);
        Map<String, Double> out = new HashMap<>();

        for (Hit<Map> hit : response.hits().hits()) {
            Map<String, Object> src = hit.source();
            if (src == null) continue;
            Object rid = src.get("resource_id");
            if (rid == null) continue;

            String id = String.valueOf(rid);
            double score = Optional.ofNullable(hit.score()).orElse(0.0);
            out.merge(id, score, Math::max);
        }

        return out;
    }

    private static int clampNonNeg(int v) {
        return Math.max(0, v);
    }

    private static int clampPageSize(int v) {
        if (v <= 0) return 20;
        return Math.min(v, 100);
    }

    private static String normalizeMode(String mode) {
        String m = (mode == null ? "keyword" : mode).toLowerCase();
        if (!m.equals("keyword") && !m.equals("semantic") && !m.equals("hybrid")) {
            return "keyword";
        }
        return m;
    }

    private static String extractSnippet(Hit<Map> hit, Map<String, Object> src) {
        if (hit.highlight() != null && hit.highlight().get("chunk_text") != null) {
            List<String> hl = hit.highlight().get("chunk_text");
            if (hl != null && !hl.isEmpty()) return String.join(" ", hl);
        }
        Object ct = src.get("chunk_text");
        if (ct instanceof String s) {
            String t = s.strip();
            if (t.length() <= 220) return t;
            return t.substring(0, 220) + "...";
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
            List<String> statuses,
            Boolean isPinned,
            List<String> tags
    ) {
        if (resourceType != null && !resourceType.isBlank()) {
            b.filter(f -> f.term(t -> t.field("resource_type").value(resourceType)));
        }
        if (domain != null && !domain.isBlank()) {
            b.filter(f -> f.term(t -> t.field("domain").value(domain)));
        }
        if (statuses != null && !statuses.isEmpty()) {
            b.filter(f -> f.terms(t -> t.field("status").terms(tt -> tt.value(
                    statuses.stream().map(FieldValue::of).toList()
            ))));
        }
        if (isPinned != null) {
            b.filter(f -> f.term(t -> t.field("is_pinned").value(isPinned)));
        }
        if (tags != null && !tags.isEmpty()) {
            b.filter(f -> f.terms(t -> t.field("tags").terms(tt -> tt.value(
                    tags.stream().map(FieldValue::of).toList()
            ))));
        }
    }

    private static List<String> sanitizeStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return List.of();
        return statuses.stream()
                .map(s -> s == null ? "" : s.trim().toLowerCase())
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
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

        Boolean isPinned;
        String createdAt;

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

    private record ScoreParts(Double keywordScore, Double vectorScore, Double finalScore) {}

    private record SearchRunResult(PagedResponse<GroupedSearchResultDto> grouped, long esMs) {}
}
