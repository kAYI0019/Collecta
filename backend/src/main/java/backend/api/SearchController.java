package backend.api;

import backend.search.SearchService;
import backend.search.dto.GroupedSearchResultDto;
import backend.search.dto.PagedResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public PagedResponse<GroupedSearchResultDto> search(
            @RequestParam(required = false) String q,

            // filters
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean isPinned,
            @RequestParam(required = false) String tags, // "a,b,c"

            // paging
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,

            // sorting
            @RequestParam(defaultValue = "relevance") String sort // relevance|newest|pinned
    ) throws Exception {
        List<String> tagList = (tags == null || tags.isBlank())
                ? List.of()
                : List.of(tags.split("\\s*,\\s*"));

        return searchService.searchGroupedPaged(
                q, resourceType, domain, status, isPinned, tagList,
                page, pageSize, sort
        );
    }
}
