package org.example;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsController {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<org.example.NewsDto> ROW_MAPPER = (rs, rowNum) -> {
        NewsDto dto = new NewsDto();
        dto.setUrl(rs.getString("url"));
        dto.setTitle(rs.getString("title"));
        dto.setDescription(rs.getString("description"));
        dto.setScore(rs.getDouble("score"));
        dto.setKeywordMatches(rs.getInt("keyword_matches"));
        dto.setCrawledAt(rs.getTimestamp("crawled_at") != null
                ? rs.getTimestamp("crawled_at").toInstant()
                : null);
        return dto;
    };

    @GetMapping
    public List<NewsDto> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String sql = """
                SELECT url, title, h1 as description, score, keyword_matches, crawled_at 
                FROM pages 
                WHERE (?::text IS NULL OR title ILIKE ? OR description ILIKE ?)
                ORDER BY score DESC, crawled_at DESC
                LIMIT ? OFFSET ?
                """;

        String pattern = keyword != null ? "%" + keyword.toLowerCase() + "%" : null;
        int offset = Math.max(0, page) * size;

        return jdbcTemplate.query(sql, ROW_MAPPER,
                pattern, pattern, pattern, size, offset);
    }

    @GetMapping("/{id}")
    public NewsDto getById(@PathVariable Long id) {
        String sql = "SELECT url, title, h1 as description, score, keyword_matches, crawled_at FROM pages WHERE id = ?";
        return jdbcTemplate.queryForObject(sql, ROW_MAPPER, id);
    }
}
