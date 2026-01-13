package backend.search;

import backend.search.dto.SearchResultDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class KeywordSearchRepository {

    private final JdbcTemplate jdbcTemplate;

    public KeywordSearchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<SearchResultDto> SEARCH_RESULT_ROW_MAPPER = new RowMapper<SearchResultDto>() {
        @Override
        public SearchResultDto mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SearchResultDto(
                    String.valueOf(rs.getLong("resource_id")),
                    rs.getString("type"),
                    rs.getString("domain"),
                    List.of(), // tags - SQL에서 가져오지 않으므로 빈 리스트
                    rs.getString("snippet")
            );
        }
    };

    public List<SearchResultDto> search(String q) {
        String sql = """
            WITH search_query AS (
              SELECT websearch_to_tsquery('simple', ?) AS query
            )
            SELECT
              r.id AS resource_id,
              r.type,
              r.domain,
              ts_headline(
                'simple',
                c.chunk_text,
                sq.query,
                'StartSel=<em>, StopSel=</em>, MaxWords=20'
              ) AS snippet
            FROM chunks c
            JOIN resources r ON r.id = c.resource_id
            CROSS JOIN search_query sq
            WHERE c.fts @@ sq.query
            ORDER BY ts_rank_cd(c.fts, sq.query) DESC
            LIMIT 20
        """;

        return jdbcTemplate.query(sql, SEARCH_RESULT_ROW_MAPPER, q);
    }
}
