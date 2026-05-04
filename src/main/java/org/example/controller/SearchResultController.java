package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.dto.SearchResultDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchResultController {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<SearchResultDto> ROW_MAPPER = (rs, rowNum) -> {
        final SearchResultDto dto = new SearchResultDto();
        dto.setUrl(rs.getString("url"));
        dto.setTitle(rs.getString("title"));
        dto.setDescription(rs.getString("description"));
        dto.setScore(rs.getDouble("score"));
        dto.setKeywordMatches(rs.getInt("keyword_matches"));
        dto.setCrawledAt(rs.getTimestamp("crawled_at") != null
                ? rs.getTimestamp("crawled_at").toInstant()
                : null);
        dto.setDomain(rs.getString("domain"));
        dto.setContentText(rs.getString("content_text"));
        return dto;
    };

    @GetMapping
    public List<SearchResultDto> search(
            @RequestParam(required = false) final String keyword,
            @RequestParam(required = false) final String domain,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "20") final int size) {

        final String sql = """
                SELECT url, title, description, score, keyword_matches, crawled_at, domain, content_text
                FROM pages
                WHERE status = 'processed'
                  AND (?::text IS NULL OR title ILIKE ? OR description ILIKE ? OR content_text ILIKE ?)
                  AND (?::text IS NULL OR domain = ?)
                ORDER BY score DESC, crawled_at DESC
                LIMIT ? OFFSET ?
                """;

        final String pattern = keyword != null ? "%" + keyword + "%" : null;
        final int offset = Math.max(0, page) * size;

        return jdbcTemplate.query(sql, ROW_MAPPER,
                pattern, pattern, pattern, pattern,
                domain, domain,
                size, offset);
    }

    @GetMapping("/fulltext")
    public List<SearchResultDto> fullTextSearch(
            @RequestParam final String query,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "20") final int size) {

        final String sql = """
                SELECT url, title, description, score, keyword_matches, crawled_at, domain, content_text
                FROM pages
                WHERE status = 'processed'
                  AND search_vector @@ plainto_tsquery('russian', ?)
                ORDER BY ts_rank(search_vector, plainto_tsquery('russian', ?)) DESC, crawled_at DESC
                LIMIT ? OFFSET ?
                """;

        final int offset = Math.max(0, page) * size;
        return jdbcTemplate.query(sql, ROW_MAPPER, query, query, size, offset);
    }

    @GetMapping("/{id}")
    public SearchResultDto getById(@PathVariable final Long id) {
        final String sql = """
                SELECT url, title, description, score, keyword_matches, crawled_at, domain, content_text
                FROM pages WHERE id = ?
                """;
        return jdbcTemplate.queryForObject(sql, ROW_MAPPER, id);
    }
}
