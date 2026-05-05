package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.dto.SearchResultDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchResultController {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

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
        dto.setFullContent(rs.getString("full_content"));
        return dto;
    };

    @GetMapping
    public List<SearchResultDto> search(
            @RequestParam(required = false) final String keyword,
            @RequestParam(required = false) final String domain,
            @RequestParam(required = false) final Integer minMatches,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "20") final int size) {

        // 1. Собираем WHERE-условия динамически
        final List<String> whereClauses = new ArrayList<>();
        whereClauses.add("status = 'processed'"); // обязательное условие

        // 2. Подготовка параметров
        final MapSqlParameterSource params = new MapSqlParameterSource();

        // Условие по keyword
        if (keyword != null && !keyword.isBlank()) {
            whereClauses.add("(title ILIKE :pattern OR description ILIKE :pattern OR content_text ILIKE :pattern)");
            params.addValue("pattern", "%" + keyword + "%");
        }

        // Условие по domain
        if (domain != null && !domain.isBlank()) {
            whereClauses.add("domain = :domain");
            params.addValue("domain", domain);
        }

        // Условие по minMatches
        if (minMatches != null && minMatches > 0) {
            whereClauses.add("keyword_matches >= :minMatches");
            params.addValue("minMatches", minMatches);
        }

        // 3. Формируем финальный SQL
        final String where = String.join(" AND ", whereClauses);
        final int limit = Math.min(size, 100); // защита от слишком большого limit
        final int offset = Math.max(0, page) * Math.max(1, size);

        params.addValue("limit", limit);
        params.addValue("offset", offset);

        final String sql = """
                SELECT url, title, description, score, keyword_matches, crawled_at, domain, content_text, full_content
                FROM pages
                WHERE %s
                ORDER BY score DESC, crawled_at DESC
                LIMIT :limit OFFSET :offset
                """.formatted(where);

        // 4. Выполнение запроса
        return namedJdbcTemplate.query(sql, params, ROW_MAPPER);
    }

    @GetMapping("/fulltext")
    public List<SearchResultDto> fullTextSearch(
            @RequestParam final String query,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "20") final int size) {

        final String sql = """
                SELECT url, title, description, score, keyword_matches, crawled_at, domain, content_text, full_content
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
                SELECT url, title, description, score, keyword_matches, crawled_at, domain, content_text, full_content
                FROM pages WHERE id = ?
                """;
        return jdbcTemplate.queryForObject(sql, ROW_MAPPER, id);
    }
}
