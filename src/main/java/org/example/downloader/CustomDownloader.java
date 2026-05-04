package org.example.downloader;

import lombok.extern.slf4j.Slf4j;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.downloader.HttpClientDownloader;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class CustomDownloader extends HttpClientDownloader {

    private final Map<String, Integer> retryCount = new ConcurrentHashMap<>();
    private static final int MAX_RETRIES = 3;

    @Override
    public Page download(final Request request,
                         final Task task) {

        final String url = request.getUrl();
        final String jobId = request.getExtra("jobId") != null
                ? request.getExtra("jobId").toString()
                : "unknown";

        log.debug("Downloading: {} (job: {})", url, jobId);

        // Пропуск нежелательных форматов
        if (url != null && url.matches(".*\\.(pdf|jpg|png|zip|exe|gif|mp4|mp3|ico|css|js)$")) {
            final Page page = Page.ofSuccess(request);
            page.setSkip(true);
            return page;
        }

        final Page page = super.download(request, task);

        if (page != null) {
            // Обработка 429 и 5xx
            if (page.getStatusCode() == 429 || page.getStatusCode() >= 500) {
                int retries = retryCount.getOrDefault(url, 0) + 1;
                if (retries <= MAX_RETRIES) {
                    retryCount.put(url, retries);
                    long delay = (long) (1000 * Math.pow(2, retries - 1));
                    log.warn("Rate limited or server error ({}). Retry {}/{} after {}ms: {}",
                            page.getStatusCode(), retries, MAX_RETRIES, delay, url);
                    try {
                        Thread.sleep(delay);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return download(request, task);
                } else {
                    log.error("Max retries exceeded for: {}", url);
                    retryCount.remove(url);
                }
            } else {
                retryCount.remove(url);
            }
        }

        return page;
    }
}
