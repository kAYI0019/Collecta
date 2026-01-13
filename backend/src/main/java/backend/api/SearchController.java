package backend.api;

import backend.search.SearchService;
import backend.search.dto.SearchResultDto;
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
    public List<SearchResultDto> search(
            @RequestParam(required = false) String q
    ) throws Exception {
        return searchService.search(q);
    }
}