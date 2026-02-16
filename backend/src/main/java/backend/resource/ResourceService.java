package backend.resource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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

    public Optional<DocumentFile> findDocumentFile(long resourceId) {
        List<DocumentFileRow> rows = jdbc.query(
                """
                SELECT d.file_path, d.mime_type
                FROM documents d
                WHERE d.resource_id = ?
                """,
                (rs, rowNum) -> new DocumentFileRow(
                        rs.getString("file_path"),
                        rs.getString("mime_type")
                ),
                resourceId
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Path baseDir = Path.of(basePath).toAbsolutePath().normalize();
        DocumentFileRow row = rows.get(0);
        Path filePath = Path.of(row.filePath()).toAbsolutePath().normalize();

        if (!filePath.startsWith(baseDir)) {
            throw new IllegalStateException("document path is outside storage base path: " + resourceId);
        }
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            return Optional.empty();
        }

        String filename = filePath.getFileName() == null ? "file" : filePath.getFileName().toString();
        long size;
        try {
            size = Files.size(filePath);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read file size: " + filePath, e);
        }

        return Optional.of(new DocumentFile(
                filePath,
                row.mimeType(),
                filename,
                size
        ));
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

    private record DocumentFileRow(
            String filePath,
            String mimeType
    ) {}

    public record DocumentFile(
            Path filePath,
            String mimeType,
            String filename,
            long size
    ) {}
}
