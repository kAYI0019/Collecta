package backend.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final String streamKey;
    private final int batchSize;

    public OutboxPublisher(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            @Value("${outbox.publisher.stream-key:collecta:outbox:resource}") String streamKey,
            @Value("${outbox.publisher.batch-size:100}") int batchSize
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> events = jdbcTemplate.query(
                """
                SELECT id, event_type, payload::text AS payload
                FROM outbox_events
                WHERE published_at IS NULL
                ORDER BY id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """,
                ps -> ps.setInt(1, batchSize),
                (rs, rowNum) -> new OutboxEvent(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getString("payload")
                )
        );

        if (events.isEmpty()) {
            return;
        }

        for (OutboxEvent event : events) {
            Map<String, String> fields = new HashMap<>();
            fields.put("event_id", Long.toString(event.id()));
            fields.put("event_type", event.eventType());
            fields.put("payload", event.payloadJson());

            MapRecord<String, String, String> record = StreamRecords.newRecord()
                    .ofMap(fields)
                    .withStreamKey(streamKey);

            redisTemplate.opsForStream().add(record);
            jdbcTemplate.update(
                    "UPDATE outbox_events SET published_at = NOW() WHERE id = ?",
                    event.id()
            );
        }

        log.debug("Published {} outbox events to {}", events.size(), streamKey);
    }
}
