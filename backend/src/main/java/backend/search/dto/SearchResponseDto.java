package backend.search.dto;

import java.util.List;

public record SearchResponseDto(
        List<SearchResourceItemDto> items,
        int page,
        int pageSize,
        long total,
        int totalPages,
        SearchDebugMetaDto debug
) {}

