package org.example.health;

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

    public CrawlerHealthIndicator(final JedisPool jedisPool,
                                  final DataSource dataSource) {
        this.jedisPool = jedisPool;
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        final Health.Builder builder = Health.up();

        // PostgreSQL
        try (final Connection conn = dataSource.getConnection()) {
            if (conn.isValid(2)) {
                builder.withDetail("postgres", "connected");
            } else {
                builder.withDetail("postgres", "connection_invalid");
            }
        } catch (final SQLException e) {
            builder.withDetail("postgres", "error: " + e.getMessage());
        }

        // Redis
        try (final Jedis jedis = jedisPool.getResource()) {
            if ("PONG".equals(jedis.ping())) {
                builder.withDetail("redis", "connected");
            } else {
                builder.withDetail("redis", "ping_failed");
            }
        } catch (final Exception e) {
            builder.withDetail("redis", "error: " + e.getMessage());
        }

        return builder.build();
    }
}
