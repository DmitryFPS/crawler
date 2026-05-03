package org.example;

import lombok.extern.slf4j.Slf4j;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.processor.PageProcessor;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Set;

@Slf4j
public class CrawlerProcessor implements PageProcessor {

    private final MetricsService metrics;
    private final CrawlerProperties props;
    private final RankingService rankingService;
    private final List<String> keywords;

    public CrawlerProcessor(MetricsService metrics,
                            CrawlerProperties props,
                            RankingService rankingService,
                            List<String> keywords) {
        this.metrics = metrics;
        this.props = props;
        this.rankingService = rankingService;
        this.keywords = keywords;
    }

    @Override
    public void process(Page page) {
        metrics.timer().record(() -> {
            try {
                // 🚫 Фильтр по расширениям файлов
                String url = page.getUrl().get();
                if (url != null && url.matches(".*\\.(pdf|jpg|png|zip|exe|gif|mp4|mp3|ico)$")) {
                    page.setSkip(true);
                    return;
                }

                // 🚫 Опционально: ограничиваем обход одним доменом
                String startUrl = page.getRequest().getUrl();
                if (!isSameDomain(startUrl, url)) {
                    return;
                }

                String text = page.getHtml().get();
                if (text == null || text.isEmpty()) {
                    page.setSkip(true);
                    return;
                }

                PageData data = new PageData();
                data.setUrl(url);
                data.setTitle(page.getHtml().xpath("//title/text()").get());
                data.setH1(page.getHtml().xpath("//h1//text()").get());
                data.setDescription(page.getHtml()
                        .xpath("//meta[@name='description']/@content")
                        .get());

                double score = rankingService.calculateScore(
                        text,
                        data.getTitle(),
                        data.getH1(),
                        keywords
                );

                if (score == 0) {
                    page.setSkip(true);
                    return;
                }

                data.setScore(score);
                data.setKeywordMatches(keywords != null
                        ? (int) keywords.stream().filter(k -> text.toLowerCase().contains(k.toLowerCase())).count()
                        : 0);

                page.putField("data", data);

                // 🔗 Добавляем новые ссылки (только того же домена)
                List<String> links = page.getHtml().links().all();
                for (String link : links) {
                    if (isSameDomain(startUrl, link)) {
                        page.addTargetRequest(link);
                    }
                }

                metrics.processed();

            } catch (Exception e) {
                log.error("Error processing page: {}", page.getUrl(), e);
                metrics.failed();
            }
        });
    }

    // Вспомогательный метод для сравнения доменов
    private boolean isSameDomain(String url1, String url2) {
        try {
            String domain1 = new URL(url1).getHost();
            String domain2 = new URL(url2).getHost();
            return domain1 != null && domain1.equals(domain2);
        } catch (MalformedURLException e) {
            return false;
        }
    }

    @Override
    public Site getSite() {
        return Site.me()
                // ⏱️ Задержка между запросами
                .setSleepTime(props.getSleep())

                // 🔄 Повторы
                .setRetryTimes(props.getRetry().getMaxAttempts())
                .setRetrySleepTime(props.getRetry().getSleep())

                // ⏳ Таймаут
                .setTimeOut(props.getTimeout())

                // 🌐 User-Agent
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Safari/537.36")

                // 📦 Кодировка
                .setCharset("UTF-8")

                // 🎯 Принимать только HTML
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

                // 📝 Не сохранять куки автоматически (для чистоты)
                .setDisableCookieManagement(true)

                // 🎯 Принимать только успешные статус-коды
                .setAcceptStatCode(Set.of(200, 301, 302));
    }
}
