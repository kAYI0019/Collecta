package backend.outbox;

public record OutboxEvent(
        long id,
        String eventType,
        String payloadJson
) {
}
