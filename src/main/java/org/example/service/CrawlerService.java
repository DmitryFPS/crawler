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

        // Очищаем состояние Redis для этого URL, чтобы разрешить повторный краулинг
        try (Jedis jedis = jedisPool.getResource()) {
            // Ключи формата зависят от версии RedisScheduler, обычно:
            jedis.del("webmagic:redis:cache:" + startUrl);
            jedis.del("webmagic:redis:queue:" + jobId);
        } catch (final Exception e) {
            log.warn("Could not clear Redis state for {}: {}", startUrl, e.getMessage());
        }

        final CrawlerProcessor processor = new CrawlerProcessor(
                metricsService,
                properties,
                rankingService,
                request.getKeywords(),
                jobId,
                request.getMaxDepth() != null ? request.getMaxDepth() : properties.getMaxDepth()
        );

        final int threads = request.getThreads() != null && request.getThreads() > 0
                ? request.getThreads()
                : properties.getThreads();

        final Spider spider = Spider.create(processor)
                .addUrl(request.getUrl())
                .addPipeline(postgresPipeline)
                .setScheduler(new RedisScheduler(jedisPool))
                .thread(threads)
                .setDownloader(new SeleniumDownloader());

        final Thread thread = new Thread(spider::start, "crawler-" + jobId);
        thread.setDaemon(false);

        spiders.put(jobId, spider);
        spiderThreads.put(jobId, thread);
        thread.start();

        log.info("Started crawler job: {} | url: {} | threads: {}", jobId, request.getUrl(), threads);
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
