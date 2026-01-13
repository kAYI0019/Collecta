package backend.search.dto;


import java.util.List;

public record SearchResultDto(
        String resourceId,
        String resourceType,
        String domain,
        List<String> tags,
        String snippet
) {}
