package backend.api;

import backend.ingest.IngestService;
import backend.ingest.IngestStatusService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ingest")
public class IngestStatusController {

    private final IngestStatusService ingestStatusService;
    private final IngestService ingestService;

    public IngestStatusController(IngestStatusService ingestStatusService, IngestService ingestService) {
        this.ingestStatusService = ingestStatusService;
        this.ingestService = ingestService;
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

    @PostMapping("/{resourceId}/cancel")
    public IngestStatusService.IngestStatus cancel(@PathVariable long resourceId) {
        return ingestStatusService.cancel(resourceId)
                .orElseThrow(() -> new StatusNotFoundException(resourceId));
    }

    @PostMapping("/{resourceId}/retry")
    public IngestStatusService.IngestStatus retry(@PathVariable long resourceId) {
        IngestService.RetryResult result = ingestService.retryIngest(resourceId);
        if (result == IngestService.RetryResult.NOT_FOUND) {
            throw new StatusNotFoundException(resourceId);
        }
        if (result == IngestService.RetryResult.NOT_RETRYABLE) {
            throw new RetryNotAllowedException(resourceId);
        }
        return ingestStatusService.findByResourceId(resourceId)
                .orElseThrow(() -> new StatusNotFoundException(resourceId));
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static class StatusNotFoundException extends RuntimeException {
        StatusNotFoundException(long resourceId) {
            super("ingest status not found: " + resourceId);
        }
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    private static class RetryNotAllowedException extends RuntimeException {
        RetryNotAllowedException(long resourceId) {
            super("ingest job is not retryable: " + resourceId);
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
