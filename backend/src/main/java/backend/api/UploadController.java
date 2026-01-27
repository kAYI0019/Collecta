package backend.api;

import backend.ingest.IngestService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private final IngestService ingestService;

    public UploadController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping(value = "/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse uploadDocument(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String memo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "false") boolean isPinned,
            @RequestParam(required = false) String tags
    ) {
        List<String> tagList = parseTags(tags);
        long resourceId = ingestService.ingestDocument(file, title, memo, status, isPinned, tagList);
        return new UploadResponse(resourceId, "queued");
    }

    @PostMapping(value = "/link", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UploadResponse uploadLink(@RequestBody LinkUploadRequest request) {
        List<String> tagList = (request.tags() == null ? List.of() : request.tags());
        long resourceId = ingestService.ingestLink(
                request.url(),
                request.title(),
                request.memo(),
                request.status(),
                request.isPinned(),
                tagList
        );
        return new UploadResponse(resourceId, "queued");
    }

    private static List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        return List.of(tags.split("\\s*,\\s*"));
    }

    public record UploadResponse(long resourceId, String status) {}

    public record LinkUploadRequest(
            String url,
            String title,
            String memo,
            String status,
            boolean isPinned,
            List<String> tags
    ) {}
}
