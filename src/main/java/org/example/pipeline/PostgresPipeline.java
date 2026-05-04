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
        final PageContent data = items.get("pageContent");
        if (data == null) {
            return;
        }

        try (Connection conn = dataSource.getConnection();
             final PreparedStatement stmt = conn.prepareStatement("""
                     INSERT INTO pages(url, title, h1, description, content_text, 
                                      score, keyword_matches, domain, crawl_depth, status, crawled_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT (url) DO UPDATE SET
                         title = EXCLUDED.title,
                         h1 = EXCLUDED.h1,
                         description = EXCLUDED.description,
                         content_text = EXCLUDED.content_text,
                         score = EXCLUDED.score,
                         keyword_matches = EXCLUDED.keyword_matches,
                         domain = EXCLUDED.domain,
                         crawl_depth = EXCLUDED.crawl_depth,
                         status = EXCLUDED.status,
                         updated_at = CURRENT_TIMESTAMP
                     """)) {

            stmt.setString(1, data.getUrl());
            stmt.setString(2, data.getTitle());
            stmt.setString(3, data.getH1());
            stmt.setString(4, data.getDescription());
            stmt.setString(5, data.getContentText());
            stmt.setDouble(6, data.getScore());
            stmt.setInt(7, data.getKeywordMatches());
            stmt.setString(8, data.getDomain());
            stmt.setInt(9, data.getCrawlDepth());
            stmt.setString(10, data.getStatus());
            stmt.setTimestamp(11, Timestamp.from(Instant.now()));

            stmt.executeUpdate();
            log.debug("Saved page: {} (score: {})", data.getUrl(), data.getScore());

        } catch (final SQLException e) {
            log.error("Failed to save page: {}", data.getUrl(), e);
            metricsService.pageFailed();
        }
    }
}
