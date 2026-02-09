package backend.api;

import backend.search.SearchService;
import backend.search.dto.SearchResponseDto;
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
    public SearchResponseDto search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean isPinned,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false, defaultValue = "keyword") String mode,
            @RequestParam(required = false, defaultValue = "false") boolean debug,
            @RequestParam(required = false, defaultValue = "true") boolean log,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "relevance") String sort
    ) throws Exception {
        List<String> tagList = (tags == null || tags.isBlank())
                ? List.of()
                : List.of(tags.split("\\s*,\\s*"));

        return searchService.searchResourceCards(
                q, resourceType, domain, status, isPinned, tagList, page, pageSize, sort, mode, debug, log
        );
    }
}
