package backend.search;

import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String model;
    private final Integer dim;
    private final ObjectMapper objectMapper;

    public EmbeddingClient(
            @Value("${worker.base-url:http://localhost:8001}") String baseUrl,
            @Value("${worker.embedding.model:dragonkue/BGE-m3-ko}") String model,
            @Value("${worker.embedding.dim:1024}") Integer dim
    ) {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.baseUrl = baseUrl;
        this.model = model;
        this.dim = dim;
        this.objectMapper = new ObjectMapper();
    }

    public List<Double> embedOne(String text) {
        String payload = toJsonPayload(text);
        log.debug("Embedding request - URL: {}, Payload: {}", baseUrl + "/embed", payload);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/embed"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.debug("Embedding response - Status: {}, Body: {}", response.statusCode(), response.body());
        } catch (IOException | InterruptedException e) {
            log.error("Embedding request failed", e);
            throw new IllegalStateException("embedding request failed", e);
        }

        if (response.statusCode() / 100 != 2) {
            log.error("Embedding request failed: status={} body={}", response.statusCode(), response.body());
            throw new IllegalStateException("embedding request failed: " + response.statusCode());
        }

        EmbedResponse parsed;
        try {
            parsed = objectMapper.readValue(response.body(), EmbedResponse.class);
        } catch (IOException e) {
            throw new IllegalStateException("failed to parse embedding response", e);
        }

        if (parsed.embeddings() == null || parsed.embeddings().isEmpty()) {
            throw new IllegalStateException("embedding response is empty");
        }

        return parsed.embeddings().get(0);
    }

    private record EmbedResponse(List<List<Double>> embeddings, String model, Integer dim) {}

    private String toJsonPayload(String text) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "texts", List.of(text),
                    "model", model,
                    "dim", dim
            ));
        } catch (IOException e) {
            throw new IllegalStateException("failed to serialize embedding request", e);
        }
    }
}
