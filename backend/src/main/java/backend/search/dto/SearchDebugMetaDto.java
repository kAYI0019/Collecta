package backend.search.dto;

public record SearchDebugMetaDto(
        String mode,
        boolean enabled,
        long totalMs,
        Long embedMs,
        Long esMs
) {}

