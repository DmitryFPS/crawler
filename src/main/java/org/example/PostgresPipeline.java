package org.example;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import us.codecraft.webmagic.ResultItems;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.pipeline.Pipeline;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

@Component
@Slf4j
public class PostgresPipeline implements Pipeline {
    private final DataSource ds;
    private final MetricsService metricsService;

    public PostgresPipeline(DataSource ds, MetricsService metricsService) {
        this.ds = ds;
        this.metricsService = metricsService;
    }

    @Override
    public void process(ResultItems items, Task task) {
        PageData data = items.get("data");
        if (data == null) return;

        try (Connection conn = ds.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO pages(url,title,h1,description,score,keyword_matches) " +
                             "VALUES (?,?,?,?,?,?) ON CONFLICT (url) DO UPDATE SET " +
                             "title=EXCLUDED.title, score=EXCLUDED.score, keyword_matches=EXCLUDED.keyword_matches")) {

            stmt.setString(1, data.getUrl());
            stmt.setString(2, data.getTitle());
            stmt.setString(3, data.getH1());
            stmt.setString(4, data.getDescription());
            stmt.setDouble(5, data.getScore());
            stmt.setInt(6, data.getKeywordMatches());

            stmt.executeUpdate();
            log.info("Saved page: {}", data.getUrl());

        } catch (SQLException e) {
            log.error("Failed to save page: {}", data.getUrl(), e);
            if (metricsService != null) metricsService.failed();
        }
    }
}
