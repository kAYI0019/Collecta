package backend.search.dto;

import java.util.List;

public record GroupedSearchResultDto(
        String resourceId,
        String resourceType,
        String domain,
        List<String> tags,
        int matchCount,
        double bestScore,
        String bestSnippet,
        Integer bestPageIndex
) {}