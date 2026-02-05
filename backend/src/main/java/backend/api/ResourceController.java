package backend.api;

import backend.resource.ResourceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @DeleteMapping("/{resourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long resourceId) {
        boolean deleted = resourceService.deleteResource(resourceId);
        if (!deleted) {
            throw new ResourceNotFoundException(resourceId);
        }
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static class ResourceNotFoundException extends RuntimeException {
        ResourceNotFoundException(long resourceId) {
            super("resource not found: " + resourceId);
        }
    }
}

