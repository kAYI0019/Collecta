package backend.search.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record SearchResourceItemDto(
        // identity
        long resourceId,
        String type,           // link | document

        // core meta (from Postgres)
        String title,
        String memo,
        String status,         // todo | in_progress | done
        boolean isPinned,
        OffsetDateTime createdAt,

        // link-specific
        String url,
        String domain,

        // document-specific
        String filePath,
        String mimeType,
        Long fileSize,
        String sha256,

        // tags (canonical from Postgres)
        List<String> tags,

        // search meta (from Elasticsearch)
        int matchCount,
        double bestScore,
        String bestSnippet,
        Integer bestPageIndex
) {}
