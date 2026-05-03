package org.example;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component
public class CrawlerHealthIndicator implements HealthIndicator {
    private final JedisPool jedisPool;
    private final DataSource dataSource;

    public CrawlerHealthIndicator(JedisPool jedisPool, DataSource dataSource) {
        this.jedisPool = jedisPool;
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();

        // Проверка PostgreSQL
        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(2)) {
                builder.withDetail("postgres", "ok");
            }
        } catch (SQLException e) {
            builder.withDetail("postgres", "failed: " + e.getMessage());
        }

        // Проверка Redis
        try (Jedis jedis = jedisPool.getResource()) {
            if ("PONG".equals(jedis.ping())) {
                builder.withDetail("redis", "ok");
            }
        } catch (Exception e) {
            builder.withDetail("redis", "failed: " + e.getMessage());
        }

        return builder.build();
    }
}
