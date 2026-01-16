package backend.search.internal;

import java.time.OffsetDateTime;
import java.util.List;

public record ResourceMeta(
        long resourceId,
        String type,
        String title,
        String memo,
        String status,
        boolean isPinned,
        OffsetDateTime createdAt,

        String url,
        String domain,

        String filePath,
        String mimeType,
        Long fileSize,
        String sha256,

        List<String> tags
) {}
