package org.example;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;
import us.codecraft.webmagic.Spider;
import us.codecraft.webmagic.scheduler.RedisScheduler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class CrawlerService {

    private final Map<String, Spider> spiders = new ConcurrentHashMap<>();
    private final Map<String, Thread> spiderThreads = new ConcurrentHashMap<>(); // 👈 отслеживаем потоки

    private final JedisPool pool;
    private final PostgresPipeline pipeline;
    private final CrawlerProperties props;
    private final MetricsService metricsService;
    private final RankingService rankingService;


    public String start(CrawlRequest request) {
        String id = UUID.randomUUID().toString();

        CrawlerProcessor processor = new CrawlerProcessor(
                metricsService, props, rankingService, request.getKeywords()
        );

        Spider spider = Spider.create(processor)
                .addUrl(request.getUrl())
                .addPipeline(pipeline)
                .setScheduler(new RedisScheduler(pool))
                .thread(request.getThreads() != 0 ? request.getThreads() : props.getThreads())
                .setDownloader(new CustomDownloader());

        Thread thread = new Thread(spider::start, "crawler-" + id);
        thread.setDaemon(true); // не блокирует завершение приложения

        spiders.put(id, spider);
        spiderThreads.put(id, thread);
        thread.start();

        log.info("Started crawler job: {}, url: {}", id, request.getUrl());
        return id;
    }

    public void stop(String id) {
        Spider spider = spiders.get(id);
        Thread thread = spiderThreads.get(id);

        if (spider != null) {
            spider.stop();
            log.info("Stopping crawler job: {}", id);
        }

        // 👇 Ждём завершения потока, если он есть
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(5000); // ждём макс. 5 секунд
                if (thread.isAlive()) {
                    log.warn("Crawler job {} did not stop gracefully", id);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for crawler {} to stop", id, e);
            }
        }

        // 👇 Очищаем мапы
        spiders.remove(id);
        spiderThreads.remove(id);
    }

    // ✅ Новый метод: проверка статуса
    public CrawlStatus getStatus(String id) {
        Spider spider = spiders.get(id);
        if (spider == null) return CrawlStatus.NOT_FOUND;

        Thread thread = spiderThreads.get(id);
        boolean running = thread != null && thread.isAlive();

        return running ? CrawlStatus.RUNNING : CrawlStatus.FINISHED;
    }

    // ✅ DTO для статуса
    public enum CrawlStatus {
        RUNNING, FINISHED, NOT_FOUND
    }
}
