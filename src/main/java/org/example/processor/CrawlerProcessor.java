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

    // RedisScheduler автоматически обеспечит дедупликацию через Redis

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
                // === 1. Базовая валидация URL ===
                final String currentUrl = page.getUrl().get();
                if (currentUrl == null) {
                    page.setSkip(true);
                    return;
                }

                // === 2. Извлекаем контекст краулинга СРАЗУ (до любых return!) ===
                final String startUrl = page.getRequest().getExtra("startUrl") != null
                        ? page.getRequest().getExtra("startUrl").toString()
                        : currentUrl;
                final int currentDepth = parseDepth(page.getRequest().getExtra("crawlDepth"));

                // === 3. Фильтр по расширениям файлов (НЕ блокируем обход ссылок!) ===
                if (currentUrl.matches(".*\\.(pdf|jpg|png|zip|exe|gif|mp4|mp3|ico|css|js)$")) {
                    log.debug("⊗ Skipped (file extension): {}", currentUrl);
                    addFilteredLinks(page, startUrl, currentDepth); // ← продолжаем обход!
                    page.setSkip(true);
                    return;
                }

                // === 4. Проверка контента ===
                final String rawContent = page.getHtml().get();
                if (rawContent == null || rawContent.isEmpty()) {
                    log.debug("⊗ Skipped (empty content): {}", currentUrl);
                    addFilteredLinks(page, startUrl, currentDepth); // ← продолжаем обход!
                    page.setSkip(true);
                    return;
                }

                // === 5. Извлечение основного контента ===
                String articleText = extractArticleContent(rawContent);
                if (articleText.isEmpty()) {
                    articleText = extractVisibleText(rawContent);
                }

                // Если текст слишком короткий — не сохраняем, но ссылки обходим
                if (articleText.length() < properties.getFilter().getMinContentLength()) {
                    log.debug("⊗ Skipped (too short): {} | len={}", currentUrl, articleText.length());
                    addFilteredLinks(page, startUrl, currentDepth); // ← продолжаем обход!
                    page.setSkip(true);
                    return;
                }

                // === 6. Оценка релевантности ===
                final int matches = countKeywordMatches(articleText, keywords);
                final double score = rankingService.calculateScore(
                        articleText,
                        extractTitle(page),
                        extractH1(page),
                        keywords
                );

                // === 7. Сохранение в БД ТОЛЬКО при наличии релевантности ===
                final boolean hasKeywords = matches > 0 || score >= 0.1;
                if (hasKeywords) {
                    final PageContent pageData = new PageContent();
                    pageData.setUrl(UrlNormalizer.normalize(currentUrl));
                    pageData.setTitle(extractTitle(page));
                    pageData.setH1(extractH1(page));
                    pageData.setDescription(extractDescription(page));
                    pageData.setContentText(extractKeywordSnippet(articleText, keywords, 150));
                    pageData.setFullContent(articleText.length() > 20000 ? articleText.substring(0, 20000) : articleText);
                    pageData.setDomain(DomainUtils.extractDomain(currentUrl));
                    pageData.setCrawlDepth(currentDepth);
                    pageData.setKeywordMatches(matches);
                    pageData.setScore(score);
                    pageData.setStatus("processed");

                    page.putField("pageContent", pageData);
                    metrics.pageProcessed();
                    log.info("✓ Saved: {} | matches={} score={}", currentUrl, matches, score);
                } else {
                    log.debug("⊗ Skipped (no keywords): {} | matches={} score={}", currentUrl, matches, score);
                    metrics.pageSkippedByKeyword();
                }

                // === 8. ОБХОД ССЫЛОК — ВСЕГДА, если не достигнута глубина ===
                addFilteredLinks(page, startUrl, currentDepth);

            } catch (final Exception e) {
                log.error("Error processing page: {}", page.getUrl(), e);
                metrics.pageFailed();
                page.setSkip(true);
            }
        });
    }

    /**
     * Добавляет отфильтрованные ссылки в очередь.
     * дедупликацию делает RedisScheduler!
     */
    private void addFilteredLinks(final Page page,
                                  final String startUrl,
                                  final int currentDepth) {
        if (currentDepth >= maxDepth) {
            return;
        }

        final List<String> links = page.getHtml().links().all();
        int addedCount = 0;

        for (String link : links) {
            String normalized = UrlNormalizer.normalize(link);
            if (normalized == null) continue;

            // 1. Фильтр бинарных/мусорных расширений
            if (normalized.matches(".*\\.(pdf|jpg|png|zip|exe|gif|mp4|mp3|ico|css|js)$")) {
                continue;
            }

            // 2. Убираем якоря
            if (normalized.contains("#")) {
                continue;
            }

            // 3. Ограничение по домену
            if (!DomainUtils.isSameDomain(startUrl, normalized)) {
                continue;
            }

            // RedisScheduler автоматически проверит дубликаты через ключ set:<jobId>:url
            // Если ссылка уже была — она молча отбросится, не нужно visited.contains()!

            final Request newRequest = new Request(normalized);
            newRequest.putExtra("startUrl", startUrl);
            newRequest.putExtra("crawlDepth", currentDepth + 1);
            newRequest.putExtra("jobId", jobId);

            page.addTargetRequest(newRequest);
            addedCount++;

            log.debug("➕ Added to queue: {} (depth: {})", normalized, currentDepth + 1);
        }

        if (addedCount > 0) {
            log.debug("📦 Added {} links from {} (depth: {})", addedCount, startUrl, currentDepth);
        }
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

        // ОТЛАДОЧНЫЙ ЛОГ — только если есть совпадения
        if (totalMatches > 0) {
            log.info(">>> Keyword matches: keywords={}, count={}, preview='{}'",
                    keywords, totalMatches,
                    lower.substring(0, Math.min(200, lower.length())).replaceAll("\\s+", " "));
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

        // Возвращаем только если текст достаточно длинный
        return text.length() < 150 ? "" : (text.length() > 5000 ? text.substring(0, 5000) : text);
    }

    /**
     * Извлекает только основной контент статьи, удаляя навигацию, рекламу, футеры.
     */
    private String extractArticleContent(final String html) {
        if (html == null || html.isBlank()) return "";

        String content = html;

        // 1. Удаляем явно ненужные блоки
        content = content.replaceAll("(?is)<(script|style|nav|footer|header|aside|form|noscript)[^>]*>.*?</\\1>", " ");
        content = content.replaceAll("(?is)<[^>]+(class|id)[^>]*=(\"|')[^\"']*(advertisement|banner|sidebar|cookie|popup|widget|social)[^\"']*\\2[^>]*>.*?</[^>]+>", " ");

        // 2. Пробуем вытащить <article> или <main> — приоритетный контент
        Pattern articlePattern = Pattern.compile("(?is)<(article|main)[^>]*>(.*?)</\\1>", Pattern.DOTALL);
        Matcher matcher = articlePattern.matcher(content);
        if (matcher.find()) {
            content = matcher.group(2);
        } else {
            // Если нет article/main — берём <div class="content"> или аналогичный
            Pattern contentPattern = Pattern.compile("(?is)<div[^>]*class=\"[^\"]*(content|post|article|entry)[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL);
            Matcher contentMatcher = contentPattern.matcher(content);
            if (contentMatcher.find()) {
                content = contentMatcher.group(2);
            }
        }

        // 3. Удаляем все оставшиеся теги
        content = content.replaceAll("<[^>]+>", " ");

        // 4. Декодируем HTML-сущности
        content = content.replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#\\d+;", " ")
                .replaceAll("&#[xX][0-9a-fA-F]+;", " ");

        // 5. Чистим пробелы и обрезаем
        content = content.replaceAll("\\s+", " ").trim();

        // 6. Возвращаем только если достаточно длинный
        return content.length() < properties.getFilter().getMinContentLength() ? "" : content;
    }

    /**
     * Возвращает сниппет текста вокруг первого вхождения ключевого слова.
     */
    private String extractKeywordSnippet(String content, List<String> keywords, int contextChars) {
        if (content == null || keywords == null) return "";
        String lower = content.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            int pos = lower.indexOf(kw.toLowerCase());
            if (pos != -1) {
                int start = Math.max(0, pos - contextChars);
                int end = Math.min(content.length(), pos + kw.length() + contextChars);
                String snippet = content.substring(start, end).replaceAll("\\s+", " ").trim();
                return (start > 0 ? "…" : "") + snippet + (end < content.length() ? "…" : "");
            }
        }
        return content.length() > 300 ? content.substring(0, 300) + "…" : content;
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
