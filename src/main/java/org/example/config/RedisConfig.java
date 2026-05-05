package org.example.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Configuration
@ConfigurationProperties(prefix = "crawler.redis")
@Getter
@Setter
@Slf4j
public class RedisConfig {
    private String host;
    private int port;

    @PostConstruct
    public void logConfig() {
        log.info(">>> RedisConfig: host='{}', port={}", host, port);
        if (host == null || host.isBlank()) {
            log.error(">>> Redis host is NULL or empty! Check application-{}.yml",
                    System.getProperty("spring.profiles.active", "default"));
        }

        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                log.error("Global uncaught exception in thread {}: {}", t.getName(), e.getMessage(), e)
        );
    }

    @Bean
    public JedisPool jedisPool() {
        final JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(50);
        config.setMaxIdle(10);
        config.setMinIdle(2);
        config.setTestOnBorrow(true);
        return new JedisPool(config, host, port);
    }
}
