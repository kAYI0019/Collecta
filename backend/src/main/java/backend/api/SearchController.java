package backend.api;

import backend.search.SearchService;
import backend.search.dto.PagedResponse;
import backend.search.dto.SearchResourceItemDto;
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
    public PagedResponse<SearchResourceItemDto> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean isPinned,
            @RequestParam(required = false) String tags,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "relevance") String sort
    ) throws Exception {
        List<String> tagList = (tags == null || tags.isBlank())
                ? List.of()
                : List.of(tags.split("\\s*,\\s*"));

        return searchService.searchResourceCards(
                q, resourceType, domain, status, isPinned, tagList, page, pageSize, sort
        );
    }
}
