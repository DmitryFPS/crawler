package org.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import us.codecraft.webmagic.scheduler.RedisScheduler;

@Configuration
public class WebMagicConfig {

    @Bean
    public RedisScheduler redisScheduler(final JedisPool jedisPool) {
        return new RedisScheduler(jedisPool);
    }
}
