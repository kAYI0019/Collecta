package backend.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import backend.resource.ResourceContentService;
import backend.resource.ResourceService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceService resourceService;
    private final ResourceContentService resourceContentService;

    public ResourceController(
            ResourceService resourceService,
            ResourceContentService resourceContentService
    ) {
        this.resourceService = resourceService;
        this.resourceContentService = resourceContentService;
    }

    @DeleteMapping("/{resourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long resourceId) {
        boolean deleted = resourceService.deleteResource(resourceId);
        if (!deleted) {
            throw new ResourceNotFoundException(resourceId);
        }
    }

    @GetMapping
    public ResourceService.ResourceListResponse list(
            @RequestParam(required = false) String statuses,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) Boolean isPinned,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        List<String> statusList = parseCsv(statuses);
        return resourceService.listResources(statusList, resourceType, isPinned, page, pageSize);
    }

    @GetMapping("/{resourceId}/file")
    public ResponseEntity<Resource> openFile(@PathVariable long resourceId) {
        ResourceService.DocumentFile doc = resourceService.findDocumentFile(resourceId)
                .orElseThrow(() -> new ResourceNotFoundException(resourceId));

        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (doc.mimeType() != null && !doc.mimeType().isBlank()) {
            try {
                mediaType = MediaType.parseMediaType(doc.mimeType());
            } catch (Exception ignored) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }
        }

        ContentDisposition contentDisposition = ContentDisposition.inline()
                .filename(doc.filename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(doc.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(new FileSystemResource(doc.filePath()));
    }

    @GetMapping("/{resourceId}/content")
    public ResourceContentService.ResourceContentResponse getContent(
            @PathVariable long resourceId,
            @RequestParam(required = false) String q
    ) throws Exception {
        return resourceContentService.findResourceContent(resourceId, q)
                .orElseThrow(() -> new ResourceNotFoundException(resourceId));
    }

    @PatchMapping("/{resourceId}")
    public ResourceUpdateResponse updateResource(
            @PathVariable long resourceId,
            @RequestBody(required = false) ResourceUpdatePatchRequest request
    ) {
        ResourceService.ResourcePatch patch = request == null
                ? ResourceService.ResourcePatch.empty()
                : request.toPatch();
        try {
            ResourceService.ResourceUpdateResponse response = resourceService.updateResource(resourceId, patch)
                    .orElseThrow(() -> new ResourceNotFoundException(resourceId));
            return new ResourceUpdateResponse(
                    response.resourceId(),
                    response.updated(),
                    response.resourceType(),
                    response.title(),
                    response.memo(),
                    response.tags(),
                    response.status(),
                    response.isPinned(),
                    response.url()
            );
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PatchMapping("/{resourceId}/status")
    public ResourceStatusUpdateResponse updateStatus(
            @PathVariable long resourceId,
            @RequestBody ResourceStatusUpdateRequest request
    ) {
        String nextStatus = (request == null ? null : request.status());
        ResourceService.StatusUpdateOutcome outcome = resourceService.updateResourceStatus(resourceId, nextStatus);
        if (outcome.result() == ResourceService.StatusUpdateResult.NOT_FOUND) {
            throw new ResourceNotFoundException(resourceId);
        }
        if (outcome.result() == ResourceService.StatusUpdateResult.INVALID_STATUS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status (allowed: todo, in_progress, done)");
        }
        return new ResourceStatusUpdateResponse(resourceId, outcome.status());
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static class ResourceNotFoundException extends RuntimeException {
        ResourceNotFoundException(long resourceId) {
            super("resource not found: " + resourceId);
        }
    }

    private static List<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return List.of(raw.split("\\s*,\\s*")).stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    public record ResourceStatusUpdateRequest(String status) {}
    public record ResourceStatusUpdateResponse(long resourceId, String status) {}

    public record ResourceUpdateResponse(
            long resourceId,
            boolean updated,
            String resourceType,
            String title,
            String memo,
            List<String> tags,
            String status,
            boolean isPinned,
            String url
    ) {}

    public static class ResourceUpdatePatchRequest {
        private String title;
        private String memo;
        private List<String> tags;
        private String status;
        private Boolean isPinned;
        private String url;

        @JsonIgnore
        private final Set<String> providedFields = new HashSet<>();

        @JsonSetter("title")
        public void setTitle(String title) {
            this.title = title;
            providedFields.add("title");
        }

        @JsonSetter("memo")
        public void setMemo(String memo) {
            this.memo = memo;
            providedFields.add("memo");
        }

        @JsonSetter("tags")
        public void setTags(List<String> tags) {
            this.tags = tags;
            providedFields.add("tags");
        }

        @JsonSetter("status")
        public void setStatus(String status) {
            this.status = status;
            providedFields.add("status");
        }

        @JsonSetter("isPinned")
        public void setIsPinned(Boolean isPinned) {
            this.isPinned = isPinned;
            providedFields.add("isPinned");
        }

        @JsonSetter("url")
        public void setUrl(String url) {
            this.url = url;
            providedFields.add("url");
        }

        public ResourceService.ResourcePatch toPatch() {
            return new ResourceService.ResourcePatch(
                    providedFields.contains("title"),
                    title,
                    providedFields.contains("memo"),
                    memo,
                    providedFields.contains("tags"),
                    tags,
                    providedFields.contains("status"),
                    status,
                    providedFields.contains("isPinned"),
                    isPinned,
                    providedFields.contains("url"),
                    url
            );
        }
    }
}
