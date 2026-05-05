package org.example.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.example.dto.PageContent;
import org.example.service.MetricsService;
import org.springframework.stereotype.Component;
import us.codecraft.webmagic.ResultItems;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.pipeline.Pipeline;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

@Component
@Slf4j
public class PostgresPipeline implements Pipeline {

    private final DataSource dataSource;
    private final MetricsService metricsService;

    public PostgresPipeline(final DataSource dataSource,
                            final MetricsService metricsService) {
        this.dataSource = dataSource;
        this.metricsService = metricsService;
    }

    @Override
    public void process(final ResultItems items,
                        final Task task) {

        log.info(">>> PostgresPipeline.process() called. pageContent={}",
                items.get("pageContent") != null
                        ? ((PageContent) items.get("pageContent")).getUrl()
                        : "NULL");

        final PageContent data = items.get("pageContent");
        if (data == null) {
            log.debug("PostgresPipeline: skipping - no pageContent in ResultItems for task {}",
                    task.getUUID());

            return;
        }

        try (Connection conn = dataSource.getConnection();
             final PreparedStatement stmt = conn.prepareStatement("""
                     INSERT INTO pages(url, title, h1, description, content_text, full_content,
                                      score, keyword_matches, domain, crawl_depth, status, crawled_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT (url) DO UPDATE SET
                         title = EXCLUDED.title,
                         h1 = EXCLUDED.h1,
                         description = EXCLUDED.description,
                         content_text = EXCLUDED.content_text,
                         full_content = EXCLUDED.full_content,
                         score = EXCLUDED.score,
                         keyword_matches = EXCLUDED.keyword_matches,
                         domain = EXCLUDED.domain,
                         crawl_depth = EXCLUDED.crawl_depth,
                         status = EXCLUDED.status,
                         updated_at = CURRENT_TIMESTAMP
                     """)) {

            // === Устанавливаем параметры (индексы 1-12) ===
            stmt.setString(1, data.getUrl());
            stmt.setString(2, data.getTitle());
            stmt.setString(3, data.getH1());
            stmt.setString(4, data.getDescription());
            stmt.setString(5, data.getContentText());
            stmt.setString(6, data.getFullContent());  // 6-й параметр
            stmt.setDouble(7, data.getScore());         // ← стало 7
            stmt.setInt(8, data.getKeywordMatches());   // ← сдвинуто
            stmt.setString(9, data.getDomain());        // ← сдвинуто
            stmt.setInt(10, data.getCrawlDepth());      // ← сдвинуто
            stmt.setString(11, data.getStatus());       // ← сдвинуто
            stmt.setTimestamp(12, Timestamp.from(Instant.now())); // ← сдвинуто

            stmt.executeUpdate();
            log.info("✓ Saved page: {} (score: {}, matches: {})",
                    data.getUrl(), data.getScore(), data.getKeywordMatches());

        } catch (final SQLException e) {
            log.error("✗ Failed to save page: {}", data.getUrl(), e);
            metricsService.pageFailed();
        }
    }
}
