package org.example.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.downloader.SeleniumDownloader;
import org.example.dto.CrawlRequest;
import org.example.dto.CrawlStatusDto;
import org.example.pipeline.PostgresPipeline;
import org.example.processor.CrawlerProcessor;
import org.example.properties.CrawlerProperties;
import org.example.util.UrlNormalizer;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import us.codecraft.webmagic.Spider;
import us.codecraft.webmagic.scheduler.RedisScheduler;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class CrawlerService {

    private final Map<String, Spider> spiders = new ConcurrentHashMap<>();
    private final Map<String, Thread> spiderThreads = new ConcurrentHashMap<>();

    private final JedisPool jedisPool;
    private final PostgresPipeline postgresPipeline;
    private final CrawlerProperties properties;
    private final MetricsService metricsService;
    private final RankingService rankingService;

    public String start(CrawlRequest request) {
        final String jobId = UUID.randomUUID().toString();
        final String startUrl = UrlNormalizer.normalize(request.getUrl());

        // Надёжная очистка состояния Redis
        try (Jedis jedis = jedisPool.getResource()) {
            // WebMagic RedisScheduler использует ключи формата:
            // queue_<uuid>, set_<uuid>:url, item_<uuid>:<url>
            jedis.del("queue_" + jobId);
            jedis.del("set_" + jobId + ":url");

            // Также очищаем по старому формату (на всякий случай)
            jedis.del("webmagic:cache:" + startUrl);
            jedis.del("webmagic:queue:" + jobId);

            log.info("[REDIS] Cleared state for job: {}", jobId);
        } catch (final Exception e) {
            log.warn("Could not clear Redis state for {}: {}", jobId, e.getMessage());
        }

        final CrawlerProcessor processor = new CrawlerProcessor(
                metricsService,
                properties,
                rankingService,
                request.getKeywords(),
                jobId,
                request.getMaxDepth() != null ? request.getMaxDepth() : properties.getMaxDepth(),
                properties.getMaxPagesPerJob()
        );

        final int threads = request.getThreads() != null && request.getThreads() > 0
                ? request.getThreads()
                : properties.getThreads();

        String seleniumHub = System.getenv("SELENIUM_HUB_URL");
        if (seleniumHub == null || seleniumHub.isBlank()) {
            seleniumHub = "http://localhost:4444/wd/hub";
        }

        final Spider spider = Spider.create(processor);
        spider.setUUID(jobId);  // Сначала UUID!
        spider.setScheduler(new RedisScheduler(jedisPool));  // Потом scheduler

        // Поддержка как одиночного url, так и списка seedUrls
        if (request.getSeedUrls() != null && !request.getSeedUrls().isEmpty()) {
            for (String seedUrl : request.getSeedUrls()) {
                spider.addUrl(UrlNormalizer.normalize(seedUrl));
                log.debug("Added seed URL: {}", seedUrl);
            }
        } else if (request.getUrl() != null) {
            spider.addUrl(UrlNormalizer.normalize(request.getUrl()));
        }

        spider.addPipeline(postgresPipeline)
                .thread(threads)
                .setDownloader(new SeleniumDownloader(seleniumHub));

        // Проверяем очередь с ПРАВИЛЬНЫМ форматом ключа
        try (Jedis jedis = jedisPool.getResource()) {
            String queueKey = "queue_" + jobId;  // ← правильный формат!
            Long queueSize = jedis.llen(queueKey);
            log.info("[REDIS] Queue '{}' size: {}", queueKey, queueSize);

            // Также проверьте ключи с префиксом "queue_" и "set_"
            var queueKeys = jedis.keys("queue_" + jobId + "*");
            var setKeys = jedis.keys("set_" + jobId + "*");
            log.info("[REDIS] Found queue keys: {}, set keys: {}", queueKeys, setKeys);
        }

        log.info("Registering PostgresPipeline for job {}", jobId);

        final Thread thread = new Thread(spider::start, "crawler-" + jobId);
        thread.setDaemon(false);

        spiders.put(jobId, spider);
        spiderThreads.put(jobId, thread);

        thread.setUncaughtExceptionHandler((t, e) ->
                log.error("Uncaught exception in crawler thread {}: {}", t.getName(), e.getMessage(), e)
        );

        thread.start();

        // Логирование перед стартом
        log.info("Starting crawler job: {} | url: {} | keywords: {} | depth: {} | threads: {}",
                jobId, startUrl, request.getKeywords(),
                request.getMaxDepth() != null ? request.getMaxDepth() : properties.getMaxDepth(),
                threads);

        return jobId;
    }

    public void stop(final String jobId) {
        final Spider spider = spiders.get(jobId);
        final Thread thread = spiderThreads.get(jobId);

        if (spider != null) {
            spider.stop();
            log.info("Stopping crawler job: {}", jobId);
        }

        if (thread != null && thread.isAlive()) {
            try {
                thread.join(5000);
                if (thread.isAlive()) {
                    log.warn("Crawler job {} did not stop gracefully", jobId);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for crawler {} to stop", jobId, e);
            }
        }

        spiders.remove(jobId);
        spiderThreads.remove(jobId);
    }

    public Optional<CrawlStatusDto> getStatus(final String jobId) {
        final Spider spider = spiders.get(jobId);
        if (spider == null) {
            return Optional.empty();
        }

        final Thread thread = spiderThreads.get(jobId);
        final boolean running = thread != null && thread.isAlive();

        final CrawlStatusDto status = new CrawlStatusDto();
        status.setJobId(jobId);
        status.setStatus(running ? "running" : "finished");
        status.setProcessedCount(spider.getPageCount());

        return Optional.of(status);
    }
}
