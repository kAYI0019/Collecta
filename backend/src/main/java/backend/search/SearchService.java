package backend.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import backend.search.dto.SearchResultDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SearchService {

    private static final String INDEX = "collecta_chunks";

    private final ElasticsearchClient es;

    public SearchService(ElasticsearchClient es) {
        this.es = es;
    }

    public List<SearchResultDto> search(String q) throws Exception {
        if (q == null || q.isBlank()) return List.of();

        SearchRequest request = SearchRequest.of(s -> s
                .index(INDEX)
                .size(20)
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

        List<SearchResultDto> results = new ArrayList<>();

        response.hits().hits().forEach(hit -> {
            Map<String, Object> src = hit.source();
            if (src == null) return;

            String snippet = null;
            if (hit.highlight() != null && hit.highlight().get("chunk_text") != null) {
                snippet = String.join(" ", hit.highlight().get("chunk_text"));
            }

            results.add(new SearchResultDto(
                    (String) src.get("resource_id"),
                    (String) src.get("resource_type"),
                    (String) src.get("domain"),
                    (List<String>) src.get("tags"),
                    snippet
            ));
        });

        return results;
    }
}
