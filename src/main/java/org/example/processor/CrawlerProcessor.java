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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final int maxPagesPerJob;

    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);

    // === ZERO-CONFIG: Универсальные паттерны для любых сайтов ===
    private static final Pattern ARTICLE_PATTERN = Pattern.compile(
            "(/article/|/news/|/post/|/blog/|/p/\\d|/\\d{4}/\\d{2}/|-[0-9]{4,}$|/show/|/read/|/story/|/entry/|/items/|/view/|/amp/)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NAVIGATION_PATTERN = Pattern.compile(
            "(/rubric/|/category/|/tag/|/archive/|/page/|\\?page=|\\?p=|/all/|/list/|sitemap|/feed/|/section/|/topics/|/author/)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RSS_PATTERN = Pattern.compile(
            "(\\.rss|/rss|/feed|atom\\.xml|rss\\.xml)",
            Pattern.CASE_INSENSITIVE
    );

    // Адаптивные лимиты (на страницу)
    private static final int MAX_NAV_LINKS_PER_PAGE = 6;
    private static final int MAX_RSS_LINKS_PER_PAGE = 2;

    // Кэш для предотвращения повторной обработки навигации в рамках одного job
    private final ConcurrentHashMap<String, Boolean> processedNavUrls = new ConcurrentHashMap<>();

    // Минимальная длина контента для статьи (отсекает меню/погоду)
    private static final int MIN_ARTICLE_LENGTH = 400;

    public CrawlerProcessor(final MetricsService metrics,
                            final CrawlerProperties properties,
                            final RankingService rankingService,
                            final List<String> keywords,
                            final String jobId,
                            final int maxDepth,
                            final int maxPagesPerJob) {
        this.metrics = metrics;
        this.properties = properties;
        this.rankingService = rankingService;
        this.keywords = keywords;
        this.jobId = jobId;
        this.maxDepth = maxDepth;
        this.maxPagesPerJob = maxPagesPerJob;
    }

    @Override
    public void process(final Page page) {
        metrics.getProcessingTimer().record(() -> {
            try {
                // === Глобальный лимит страниц ===
                int currentCrawled = processedCount.incrementAndGet();
                if (maxPagesPerJob > 0 && currentCrawled > maxPagesPerJob) {
                    log.info("🛑 Page limit reached ({}/{}), stopping job {}", currentCrawled, maxPagesPerJob, jobId);
                    shouldStop.set(true);
                    page.setSkip(true);
                    return;
                }

                final String currentUrl = page.getUrl().get();
                if (currentUrl == null) {
                    page.setSkip(true);
                    return;
                }

                final String startUrl = page.getRequest().getExtra("startUrl") != null
                        ? page.getRequest().getExtra("startUrl").toString() : currentUrl;
                final int currentDepth = parseDepth(page.getRequest().getExtra("crawlDepth"));

                // === Фильтр по расширениям ===
                if (currentUrl.matches(".*\\.(pdf|jpg|png|zip|exe|gif|mp4|mp3|ico|css|js)$")) {
                    addFilteredLinks(page, startUrl, currentDepth);
                    page.setSkip(true);
                    return;
                }

                // === Проверка контента ===
                final String rawContent = page.getHtml().get();
                if (rawContent == null || rawContent.isEmpty()) {
                    addFilteredLinks(page, startUrl, currentDepth);
                    page.setSkip(true);
                    return;
                }

                // === Извлечение текста ===
                String mainContent = extractArticleContent(rawContent);
                if (mainContent.isEmpty()) {
                    mainContent = extractVisibleText(rawContent);
                }

                // === 🔥 КОНТЕКСТНАЯ ПРОВЕРКА КЛЮЧЕВЫХ СЛОВ ===
                final String title = extractTitle(page);
                final String h1 = extractH1(page);

                // Считаем вхождения в ЗАГОЛОВКАХ (высокий приоритет)
                final int headerMatches = countKeywordMatches(title + " " + h1, keywords);

                // Считаем вхождения в ОСНОВНОМ контенте (не навигация!)
                final int contentMatches = countKeywordMatches(mainContent, keywords);

                // Считаем вхождения во всём тексте (для сравнения/отладки)
                final int allTextMatches = countKeywordMatches(rawContent, keywords);

                // === 🔥 ЛОГИКА СОХРАНЕНИЯ: только если ключи в реальном контенте ===
                final boolean hasHeaderKeywords = headerMatches > 0;
                final boolean hasContentKeywords = contentMatches > 0;
                final boolean isLongEnough = mainContent.length() >= MIN_ARTICLE_LENGTH;

                // Рассчитываем score только если есть потенциальная релевантность
                double score = 0;
                if (hasHeaderKeywords || (hasContentKeywords && isLongEnough)) {
                    score = rankingService.calculateScore(mainContent, title, h1, keywords);
                }

                // Сохраняем ТОЛЬКО если:
                // 1. Ключи в заголовке И контент достаточно длинный
                // ИЛИ
                // 2. Ключи в основном контенте И контент длинный И score выше порога
                final boolean shouldSave = (hasHeaderKeywords && isLongEnough) ||
                        (hasContentKeywords && isLongEnough && score >= 0.15);

                if (shouldSave) {
                    final PageContent pageData = new PageContent();
                    pageData.setUrl(UrlNormalizer.normalize(currentUrl));
                    pageData.setTitle(title);
                    pageData.setH1(h1);
                    pageData.setDescription(extractDescription(page));
                    pageData.setContentText(extractKeywordSnippet(mainContent, keywords, 150));
                    pageData.setFullContent(mainContent.length() > 20000 ? mainContent.substring(0, 20000) : mainContent);
                    pageData.setDomain(DomainUtils.extractDomain(currentUrl));
                    pageData.setCrawlDepth(currentDepth);
                    pageData.setKeywordMatches(contentMatches); // показываем матчи в контенте, не в навигации
                    pageData.setScore(score);
                    pageData.setStatus("processed");

                    page.putField("pageContent", pageData);
                    metrics.pageProcessed();

                    log.info("✓ Saved: {} | depth={} header_matches={} content_matches={} score={}",
                            currentUrl, currentDepth, headerMatches, contentMatches, score);
                } else {
                    // 🔥 Логируем, почему страница была отклонена (для отладки)
                    if (allTextMatches > 0 && contentMatches == 0) {
                        log.debug("⊗ Skipped (keywords only in navigation): {} | all={} content={} header={}",
                                currentUrl, allTextMatches, contentMatches, headerMatches);
                    } else if (!isLongEnough) {
                        log.debug("⊗ Skipped (too short for article): {} | len={} content_matches={}",
                                currentUrl, mainContent.length(), contentMatches);
                    } else if (headerMatches == 0 && contentMatches > 0 && score < 0.15) {
                        log.debug("⊗ Skipped (low relevance score): {} | score={} content_matches={}",
                                currentUrl, score, contentMatches);
                    } else {
                        log.debug("⊗ Skipped (no relevant keywords): {} | header={} content={}",
                                currentUrl, headerMatches, contentMatches);
                    }
                    metrics.pageSkippedByKeyword();
                }

                // === Обход ссылок ===
                addFilteredLinks(page, startUrl, currentDepth);

            } catch (final Exception e) {
                log.error("Error processing {}: {}", page.getUrl(), e.getMessage());
                metrics.pageFailed();
                page.setSkip(true);
            }
        });
    }

    /**
     * АДАПТИВНАЯ фильтрация: работает для любых сайтов без настройки.
     * - Статьи и RSS добавляются всегда
     * - Навигация ограничивается (чтобы не забивать очередь)
     * - Дедупликация через RedisScheduler
     */
    private void addFilteredLinks(final Page page, final String startUrl, final int currentDepth) {
        if (currentDepth >= maxDepth || shouldStop.get()) {
            return;
        }

        final List<String> links = page.getHtml().links().all();
        int addedCount = 0, navAdded = 0, rssAdded = 0;

        final boolean allowCrossDomain = properties.getFilter().isAllowCrossDomain();
        final Set<String> allowedDomains = properties.getFilter().getAllowedDomains();
        final Set<String> blockedDomains = properties.getFilter().getBlockedDomains();
        final String startDomain = DomainUtils.extractDomain(startUrl);

        for (String link : links) {
            String normalized = UrlNormalizer.normalize(link);
            if (normalized == null) continue;

            // Базовые фильтры
            if (normalized.matches(".*\\.(pdf|jpg|png|zip|exe|gif|mp4|mp3|ico|css|js|woff|woff2|ttf|svg)$")) continue;
            if (normalized.contains("#")) continue;

            final String targetDomain = DomainUtils.extractDomain(normalized);
            if (targetDomain == null) continue;

            // Доменные проверки
            if (blockedDomains.contains(targetDomain)) continue;
            if (!allowCrossDomain && !targetDomain.equals(startDomain)) continue;
            if (!allowedDomains.isEmpty() && !allowedDomains.contains(targetDomain)) continue;

            // === АДАПТИВНАЯ КЛАССИФИКАЦИЯ ===
            boolean isArticle = ARTICLE_PATTERN.matcher(normalized).find();
            boolean isNav = NAVIGATION_PATTERN.matcher(normalized).find();
            boolean isRss = RSS_PATTERN.matcher(normalized).find();

            // Приоритет: статьи и RSS добавляются всегда
            if (isArticle || isRss) {
                // RSS лимитируем, чтобы не добавлять десятки фидов
                if (isRss && rssAdded >= MAX_RSS_LINKS_PER_PAGE) continue;

                addRequest(page, normalized, startUrl, currentDepth, true);
                addedCount++;
                if (isRss) rssAdded++;
                continue;
            }

            // Навигация: ограничиваем, но не блокируем полностью
            if (isNav) {
                // Пропускаем, если уже обрабатывали эту навигацию в рамках job
                if (processedNavUrls.putIfAbsent(normalized, true) != null) continue;
                // Лимит на страницу
                if (navAdded >= MAX_NAV_LINKS_PER_PAGE) continue;

                addRequest(page, normalized, startUrl, currentDepth, false);
                addedCount++;
                navAdded++;
                continue;
            }

            // "Нейтральные" ссылки (не попали под паттерны) — добавляем с низким приоритетом
            addRequest(page, normalized, startUrl, currentDepth, false);
            addedCount++;
        }

        if (log.isDebugEnabled() && addedCount > 0) {
            log.debug("➕ Added {} links from {} (depth: {}, nav:{}, rss:{})",
                    addedCount, startUrl, currentDepth, navAdded, rssAdded);
        }
    }

    private void addRequest(final Page page, final String url, final String startUrl,
                            final int currentDepth, final boolean isPriority) {
        final Request req = new Request(url);
        req.putExtra("startUrl", startUrl);
        req.putExtra("crawlDepth", currentDepth + 1);
        req.putExtra("jobId", jobId);
        req.putExtra("priority", isPriority ? 1 : 10);  // Приоритет для очереди
        page.addTargetRequest(req);
    }

    // === Вспомогательные методы ===

    private int countKeywordMatches(final String content, final List<String> keywords) {
        if (content == null || keywords == null || content.isBlank()) return 0;
        final String lower = content.toLowerCase(Locale.forLanguageTag("ru"));
        int total = 0;
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            final Pattern p = Pattern.compile("\\b" + Pattern.quote(kw.toLowerCase(Locale.forLanguageTag("ru"))) + "\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
            final Matcher m = p.matcher(lower);
            while (m.find()) total++;
        }
        return total;
    }

    private int parseDepth(final Object d) {
        return switch (d) {
            case Integer i -> i;
            case String s -> {
                try {
                    yield Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    yield 0;
                }
            }
            case null, default -> 0;
        };
    }

    private String extractTitle(final Page p) {
        final var t = p.getHtml().xpath("//title/text()").get();
        return t != null ? t.trim() : "";
    }

    private String extractH1(final Page p) {
        final var h = p.getHtml().xpath("//h1//text()").get();
        return h != null ? h.trim() : "";
    }

    private String extractDescription(final Page p) {
        final var d = p.getHtml().xpath("//meta[@name='description']/@content").get();
        return d != null ? d.trim() : "";
    }

    private String extractVisibleText(final String html) {
        if (html == null) return "";
        String t = html.replaceAll("(?is)<(script|style|noscript|svg|iframe)[^>]*>.*?</\\1>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&[a-z]+;|&#\\d+;|&#[xX][0-9a-f]+;", " ")
                .replaceAll("\\s+", " ").trim();
        return t.length() < 150 ? "" : (t.length() > 5000 ? t.substring(0, 5000) : t);
    }

    private String extractArticleContent(final String html) {
        if (html == null || html.isBlank()) return "";
        String c = html;

        // 🔥 Агрессивно удаляем навигацию ДО выборки <article>/<main>
        c = c.replaceAll("(?is)<(nav|header|footer|aside|menu|sidebar)[^>]*>.*?</\\1>", " ");
        c = c.replaceAll("(?is)<div[^>]*class=\"[^\"]*(nav|menu|sidebar|widget|banner|ads|promo)[^\"]*\"[^>]*>.*?</div>", " ");
        c = c.replaceAll("(?is)<ul[^>]*class=\"[^\"]*(menu|nav|breadcrumbs)[^\"]*\"[^>]*>.*?</ul>", " ");

        // Приоритетные теги для контента
        String[] selectors = {
                "(?is)<(article|main)[^>]*>(.*?)</\\1>",
                "(?is)<div[^>]*class=\"[^\"]*(content|post|article|entry|news|story)[^\"]*\"[^>]*>(.*?)</div>",
                "(?is)<section[^>]*class=\"[^\"]*(content|post|article|entry)[^\"]*\"[^>]*>(.*?)</section>"
        };
        for (String sel : selectors) {
            Matcher m = Pattern.compile(sel, Pattern.DOTALL).matcher(c);
            if (m.find()) {
                c = m.groupCount() >= 2 ? m.group(2) : m.group(1);
                break;
            }
        }

        // Финальная очистка
        c = c.replaceAll("<[^>]+>", " ")
                .replaceAll("&[a-z]+;|&#\\d+;|&#[xX][0-9a-f]+;", " ")
                .replaceAll("[\\s\\u00A0]+", " ").trim();

        return c.length() < properties.getFilter().getMinContentLength() ? "" :
                (c.length() > 50000 ? c.substring(0, 50000) : c);
    }

    private String extractKeywordSnippet(final String content, final List<String> keywords, final int ctx) {
        if (content == null || keywords == null) return "";
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            Matcher m = Pattern.compile("\\b" + Pattern.quote(kw) + "\\b", Pattern.CASE_INSENSITIVE).matcher(content);
            if (m.find()) {
                int s = Math.max(0, m.start() - ctx), e = Math.min(content.length(), m.end() + ctx);
                return (s > 0 ? "…" : "") + content.substring(s, e).replaceAll("\\s+", " ").trim() + (e < content.length() ? "…" : "");
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
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .setCycleRetryTimes(2)
                .setCharset("UTF-8")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                .setDisableCookieManagement(true)
                .setAcceptStatCode(Set.of(200, 301, 302, 404));
    }
}
