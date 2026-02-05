package backend.resource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

@Service
public class ResourceService {

    private final JdbcTemplate jdbc;
    private final String basePath;

    public ResourceService(
            JdbcTemplate jdbc,
            @Value("${storage.base-path}") String basePath
    ) {
        this.jdbc = jdbc;
        this.basePath = basePath;
    }

    @Transactional
    public boolean deleteResource(long resourceId) {
        int updated = jdbc.update("DELETE FROM resources WHERE id = ?", resourceId);

        // 파일 삭제는 best-effort (DB 트랜잭션과 분리)
        Path resourceDir = Path.of(basePath).toAbsolutePath().normalize().resolve(Long.toString(resourceId));
        deleteDirectoryQuietly(resourceDir);

        return updated > 0;
    }

    private static void deleteDirectoryQuietly(Path dir) {
        if (dir == null) return;
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}

