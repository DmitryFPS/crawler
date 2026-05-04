package org.example.processor;

import lombok.extern.slf4j.Slf4j;
import org.example.dto.PageContent;
import org.example.properties.CrawlerProperties;
import org.example.service.MetricsService;
import org.example.service.RankingService;
import org.example.util.DomainUtils;
import org.example.util.UrlNormalizer;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.processor.PageProcessor;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class CrawlerProcessor implements PageProcessor {

    private final MetricsService metrics;
    private final CrawlerProperties properties;
    private final RankingService rankingService;
    private final List<String> keywords;
    private final String jobId;
    private final int maxDepth;

    public CrawlerProcessor(final MetricsService metrics,
                            final CrawlerProperties properties,
                            final RankingService rankingService,
                            final List<String> keywords,
                            final String jobId,
                            final int maxDepth) {
        this.metrics = metrics;
        this.properties = properties;
        this.rankingService = rankingService;
        this.keywords = keywords;
        this.jobId = jobId;
        this.maxDepth = maxDepth;
    }

    @Override
    public void process(final Page page) {
        metrics.getProcessingTimer().record(() -> {
            try {
                final String currentUrl = page.getUrl().get();
                if (currentUrl == null) {
                    page.setSkip(true);
                    return;
                }

                // Фильтр по расширениям
                if (currentUrl.matches(".*\\.(pdf|jpg|png|zip|exe|gif|mp4|mp3|ico|css|js)$")) {
                    page.setSkip(true);
                    return;
                }

                // Читаем метаданные из extras (не из headers!)
                final String startUrl = page.getRequest().getExtra("startUrl") != null
                        ? page.getRequest().getExtra("startUrl").toString()
                        : currentUrl;

                final int currentDepth = parseDepth(page.getRequest().getExtra("crawlDepth"));

                // Извлечение данных
                final String content = page.getHtml().get();
                if (content == null || content.isEmpty()) {
                    page.setSkip(true);
                    return;
                }

                final PageContent pageData = new PageContent();
                pageData.setUrl(UrlNormalizer.normalize(currentUrl));
                pageData.setTitle(extractTitle(page));
                pageData.setH1(extractH1(page));
                pageData.setDescription(extractDescription(page));
                pageData.setContentText(extractVisibleText(content));
                pageData.setDomain(DomainUtils.extractDomain(currentUrl));
                pageData.setCrawlDepth(currentDepth);

                // Расчёт релевантности
                final double score = rankingService.calculateScore(
                        pageData.getContentText(),
                        pageData.getTitle(),
                        pageData.getH1(),
                        keywords
                );

                // БЛОК — с пороговыми значениями:
                // Пропускаем нерелевантные (кроме стартовой)
                // Страница проходит, если: высокий score ИЛИ достаточно вхождений ключевых слов
                if (score < 0.2 &&
                        pageData.getKeywordMatches() < 1 &&
                        !currentUrl.equals(startUrl)) {

                    metrics.pageSkippedByKeyword();
                    log.debug("Skipped page (low relevance): {} | score: {} | matches: {} | keywords: {}",
                            currentUrl, score, pageData.getKeywordMatches(), keywords);
                    page.setSkip(true);
                    return;
                }

                pageData.setScore(score);
                pageData.setKeywordMatches(countKeywordMatches(pageData.getContentText(), keywords));
                pageData.setStatus("processed");

                page.putField("pageContent", pageData);

                // Добавляем ссылки ТОЛЬКО если страница релевантна
                if (score > 0 || currentUrl.equals(startUrl)) {
                    addFilteredLinks(page, startUrl, currentDepth);
                }

                metrics.pageProcessed();

            } catch (final Exception e) {
                log.error("Error processing page: {}", page.getUrl(), e);
                metrics.pageFailed();
                page.setSkip(true);
            }
        });
    }

    private void addFilteredLinks(final Page page,
                                  final String startUrl,
                                  final int currentDepth) {
        if (currentDepth >= maxDepth) {
            return;
        }

        final List<String> links = page.getHtml().links().all();
        final Set<String> added = new HashSet<>();

        for (String link : links) {
            String normalized = UrlNormalizer.normalize(link);
            if (normalized == null || added.contains(normalized)) {
                continue;
            }

            // Фильтр по домену
            if (!DomainUtils.isSameDomain(startUrl, normalized)) {
                continue;
            }

            // Фильтр по ключевым словам в URL — управляется настройкой
            if (properties.getFilter().isUrlsByKeywords() && keywords != null && !keywords.isEmpty()) {
                if (!urlContainsKeywords(normalized, keywords)) {
                    continue;
                }
            }

            // Создаём НОВЫЙ Request вместо .clone()
            final Request newRequest = new Request(normalized);
            newRequest.putExtra("startUrl", startUrl);
            newRequest.putExtra("crawlDepth", currentDepth + 1);
            newRequest.putExtra("jobId", jobId);
            // Копируем остальные экстра, если нужно
            page.getRequest().getExtras().forEach(newRequest::putExtra);

            page.addTargetRequest(newRequest);
            added.add(normalized);
        }
    }

    private boolean urlContainsKeywords(final String url,
                                        final List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return true;
        }

        final String lower = url.toLowerCase();

        return keywords.stream()
                .anyMatch(k -> lower.contains(k.toLowerCase()));
    }

    private int countKeywordMatches(final String content, final List<String> keywords) {
        if (content == null || keywords == null) return 0;

        final String lower = content.toLowerCase(Locale.ROOT);
        int totalMatches = 0;

        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) continue;

            // Экранируем спецсимволы и добавляем границы слова
            final String kw = Pattern.quote(keyword.toLowerCase(Locale.ROOT));
            final Pattern pattern = Pattern.compile("\\b" + kw + "\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            final Matcher matcher = pattern.matcher(lower);

            while (matcher.find()) totalMatches++;
        }
        return totalMatches;
    }

    private int parseDepth(final Object depthExtra) {
        return switch (depthExtra) {
            case Integer i -> i;
            case String s -> {
                try {
                    yield Integer.parseInt(s);
                } catch (final NumberFormatException e) {
                    yield 0;
                }
            }
            case null, default -> 0;
        };
    }

    private String extractTitle(final Page page) {
        final String title = page.getHtml().xpath("//title/text()").get();
        return title != null ? title.trim() : "";
    }

    private String extractH1(final Page page) {
        final String h1 = page.getHtml().xpath("//h1//text()").get();
        return h1 != null ? h1.trim() : "";
    }

    private String extractDescription(final Page page) {
        final String desc = page.getHtml()
                .xpath("//meta[@name='description']/@content")
                .get();
        return desc != null ? desc.trim() : "";
    }

    private String extractVisibleText(final String html) {
        if (html == null) {
            return "";
        }

        String text = html;

        // 1. Удаляем <script> и содержимое
        text = text.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        // 2. Удаляем <style> и содержимое
        text = text.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        // 3. Удаляем другие ненужные блоки
        text = text.replaceAll("(?is)<(noscript|svg|iframe|object|embed|canvas)[^>]*>.*?</\\1>", " ");
        // 4. Удаляем комментарии
        text = text.replaceAll("(?s)<!--.*?-->", " ");
        // 5. Удаляем все остальные теги
        text = text.replaceAll("<[^>]+>", " ");
        // 6. Декодируем HTML-сущности
        text = text.replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#\\d+;", " ")
                .replaceAll("&#[xX][0-9a-fA-F]+;", " ");
        // 7. Чистим пробелы
        text = text.replaceAll("\\s+", " ").trim();

        // 8. Обрезаем, если слишком длинный
        return text.length() > 5000 ? text.substring(0, 5000) : text;
    }

    @Override
    public Site getSite() {
        return Site.me()
                .setSleepTime(properties.getSleep())
                .setRetryTimes(properties.getRetry().getMaxAttempts())
                .setRetrySleepTime(properties.getRetry().getSleep())
                .setTimeOut(properties.getTimeout())
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Safari/537.36")
                .setCharset("UTF-8")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                .setDisableCookieManagement(true)
                .setAcceptStatCode(Set.of(200, 301, 302));
    }
}
