package backend.api;

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

import java.nio.charset.StandardCharsets;

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

    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static class ResourceNotFoundException extends RuntimeException {
        ResourceNotFoundException(long resourceId) {
            super("resource not found: " + resourceId);
        }
    }
}
