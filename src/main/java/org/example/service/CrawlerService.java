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

import java.util.List;
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

        // === Очистка Redis ===
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del("queue_" + jobId);
            jedis.del("set_" + jobId + ":url");
            jedis.del("webmagic:cache:" + startUrl);
            log.info("[REDIS] Cleared state for job: {}", jobId);
        } catch (final Exception e) {
            log.warn("Could not clear Redis state for {}: {}", jobId, e.getMessage());
        }

        // === Создаём процессор ===
        final CrawlerProcessor processor = new CrawlerProcessor(
                metricsService,
                properties,
                rankingService,
                request.getKeywords(),
                jobId,
                request.getMaxDepth() != null ? request.getMaxDepth() : properties.getMaxDepth(),
                properties.getMaxPagesPerJob()
        );

        // === Параметры потоков ===
        final int threads = request.getThreads() != null && request.getThreads() > 0
                ? request.getThreads()
                : properties.getThreads();

        // === Selenium Hub ===
        String seleniumHub = System.getenv("SELENIUM_HUB_URL");
        if (seleniumHub == null || seleniumHub.isBlank()) {
            seleniumHub = "http://localhost:4444/wd/hub";
        }

        // === Прокси-лист (если включено) ===
        final List<String> proxyList = parseProxyList(properties.getSelenium().getProxy().getList());

        // === ИСПРАВЛЕНИЕ: передаём все параметры в SeleniumDownloader ===
        final SeleniumDownloader downloader = new SeleniumDownloader(
                seleniumHub,
                properties.getAntiBot(),      // ← AntiBot config
                proxyList                      // ← Proxy list
        );

        // === Создаём Spider ===
        final Spider spider = Spider.create(processor);
        spider.setUUID(jobId);
        spider.setScheduler(new RedisScheduler(jedisPool));

        // === Seed URLs ===
        if (request.getSeedUrls() != null && !request.getSeedUrls().isEmpty()) {
            for (String seedUrl : request.getSeedUrls()) {
                spider.addUrl(UrlNormalizer.normalize(seedUrl));
                log.debug("Added seed URL: {}", seedUrl);
            }
        } else if (request.getUrl() != null) {
            spider.addUrl(UrlNormalizer.normalize(request.getUrl()));
        }

        // === использую созданный downloader ===
        spider.addPipeline(postgresPipeline)
                .thread(threads)
                .setDownloader(downloader);

        // === Логирование очереди ===
        try (Jedis jedis = jedisPool.getResource()) {
            final String queueKey = "queue_" + jobId;
            final Long queueSize = jedis.llen(queueKey);
            log.info("[REDIS] Queue '{}' size: {}", queueKey, queueSize);
        }

        log.info("Registering PostgresPipeline for job {}", jobId);

        // === Запуск в отдельном потоке ===
        final Thread thread = new Thread(spider::start, "crawler-" + jobId);
        thread.setDaemon(false);

        spiders.put(jobId, spider);
        spiderThreads.put(jobId, thread);

        thread.setUncaughtExceptionHandler((t, e) ->
                log.error("Uncaught exception in crawler thread {}: {}", t.getName(), e.getMessage(), e)
        );

        thread.start();

        log.info("Starting crawler job: {} | url: {} | keywords: {} | depth: {} | threads: {}",
                jobId, startUrl, request.getKeywords(),
                request.getMaxDepth() != null ? request.getMaxDepth() : properties.getMaxDepth(),
                threads);

        return jobId;
    }

    /**
     * Парсит строку прокси в список: "http://p1:8080,http://p2:8080" → List
     */
    private List<String> parseProxyList(final String proxyListStr) {
        if (proxyListStr == null || proxyListStr.isBlank()) {
            return List.of();
        }
        return List.of(proxyListStr.split("\\s*,\\s*"));
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
