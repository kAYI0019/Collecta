package backend.api;

import backend.ingest.IngestStatusService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ingest")
public class IngestStatusController {

    private final IngestStatusService ingestStatusService;

    public IngestStatusController(IngestStatusService ingestStatusService) {
        this.ingestStatusService = ingestStatusService;
    }

    @GetMapping("/{resourceId}")
    public IngestStatusService.IngestStatus getStatus(@PathVariable long resourceId) {
        return ingestStatusService.findByResourceId(resourceId)
                .orElseThrow(() -> new StatusNotFoundException(resourceId));
    }

    @GetMapping("/recent")
    public List<IngestStatusService.IngestStatus> recent(
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ingestStatusService.listRecent(limit);
    }

    @PostMapping("/status")
    public IngestStatusService.IngestStatus updateStatus(@RequestBody StatusUpdateRequest request) {
        return ingestStatusService.updateStatus(
                request.resourceId(),
                request.status(),
                request.errorMessage()
        ).orElseThrow(() -> new StatusNotFoundException(request.resourceId()));
    }

    @PostMapping("/progress")
    public IngestStatusService.IngestStatus updateProgress(@RequestBody ProgressUpdateRequest request) {
        return ingestStatusService.updateProgress(
                request.resourceId(),
                request.stage(),
                request.totalUnits(),
                request.processedUnits()
        ).orElseThrow(() -> new StatusNotFoundException(request.resourceId()));
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static class StatusNotFoundException extends RuntimeException {
        StatusNotFoundException(long resourceId) {
            super("ingest status not found: " + resourceId);
        }
    }

    public record StatusUpdateRequest(long resourceId, String status, String errorMessage) {}

    public record ProgressUpdateRequest(
            long resourceId,
            String stage,
            Integer totalUnits,
            Integer processedUnits
    ) {}
}
