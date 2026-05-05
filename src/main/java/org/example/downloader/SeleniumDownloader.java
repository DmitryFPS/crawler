package org.example.downloader;

import lombok.extern.slf4j.Slf4j;
import org.example.properties.CrawlerProperties;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.downloader.Downloader;
import us.codecraft.webmagic.selector.PlainText;

import java.net.URL;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class SeleniumDownloader implements Downloader, AutoCloseable {

    private final String seleniumHubUrl;
    private final CrawlerProperties.AntiBot antiBotConfig;
    private final List<String> proxyList;
    private final Random random = ThreadLocalRandom.current();

    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    };

    public SeleniumDownloader(final String hubUrl,
                              final CrawlerProperties.AntiBot antiBotConfig,
                              final List<String> proxyList) {
        this.seleniumHubUrl = Objects.requireNonNullElse(hubUrl, "http://localhost:4444/wd/hub");
        this.antiBotConfig = antiBotConfig;
        this.proxyList = proxyList != null ? proxyList : List.of();
        log.info("SeleniumDownloader initialized: hub={}, proxies={}, anti-bot={}",
                seleniumHubUrl, proxyList.size(), antiBotConfig != null);
    }

    @Override
    public Page download(final Request request, final Task task) {
        final String pageUrl = request.getUrl();
        if (pageUrl == null || pageUrl.isBlank()) {
            log.error("Empty URL in request");
            return createErrorPage(request, "Empty URL");
        }

        WebDriver driver = null;
        try {
            // 🔥 Рандомная задержка перед запросом (анти-бот)
            if (antiBotConfig != null && antiBotConfig.getRandomDelay() != null) {
                Thread.sleep(random.nextInt(
                        antiBotConfig.getRandomDelay().getMin(),
                        antiBotConfig.getRandomDelay().getMax() + 1));
            }

            final ChromeOptions options = configureChromeOptions(pageUrl);
            final URL hubUrl = new URL(seleniumHubUrl);

            driver = new RemoteWebDriver(hubUrl, options);
            configureDriver(driver);
            applyAntiBotScripts(driver);

            log.debug("Navigating to: {}", pageUrl);
            driver.get(pageUrl);

            final String finalUrl = driver.getCurrentUrl();
            if (!finalUrl.equals(pageUrl)) {
                log.debug("Redirect: {} -> {}", pageUrl, finalUrl);
                if (isCaptchaPage(finalUrl, driver)) {
                    log.warn("CAPTCHA detected at: {}", finalUrl);
                    // Попытка повторить через случайную задержку (иногда помогает)
                    if (shouldRetryCaptcha()) {
                        Thread.sleep(random.nextInt(5000, 15001));
                        driver.navigate().refresh();
                        Thread.sleep(3000);
                        if (isCaptchaPage(driver.getCurrentUrl(), driver)) {
                            return createErrorPage(request, "CAPTCHA_BLOCKED");
                        }
                    } else {
                        return createErrorPage(request, "CAPTCHA_BLOCKED");
                    }
                }
            }

            waitForContent(driver);
            final String html = driver.getPageSource();

            final Page page = new Page();
            page.setRequest(request);
            page.setRawText(html);
            page.setUrl(new PlainText(finalUrl));
            page.setStatusCode(200);
            page.setDownloadSuccess(true);

            log.debug("Downloaded: {} ({} chars)", finalUrl, html.length());
            return page;

        } catch (final Exception e) {
            log.error("Failed to download {}: {}", pageUrl, e.getMessage());
            return createErrorPage(request, e.getMessage());
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (final Exception e) {
                    log.warn("Error closing WebDriver: {}", e.getMessage());
                }
            }
        }
    }

    private ChromeOptions configureChromeOptions(final String pageUrl) {
        final ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
                "--disable-gpu", "--window-size=1920,1080", "--disable-blink-features=AutomationControlled");
        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        options.setExperimentalOption("useAutomationExtension", false);
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);

        // Ротация User-Agent
        if (antiBotConfig != null && Boolean.TRUE.equals(antiBotConfig.getUserAgentRotation())) {
            options.addArguments("--user-agent=" + USER_AGENTS[random.nextInt(USER_AGENTS.length)]);
        }

        // Прокси (если включено)
        if (!proxyList.isEmpty()) {
            final String proxy = proxyList.get(random.nextInt(proxyList.size()));
            options.setProxy(new org.openqa.selenium.Proxy().setHttpProxy(proxy).setSslProxy(proxy));
            log.debug("Using proxy: {}", proxy);
        }

        // Дополнительные настройки приватности
        final Map<String, Object> prefs = new HashMap<>();
        prefs.put("credentials_enable_service", false);
        prefs.put("profile.password_manager_enabled", false);
        prefs.put("profile.default_content_setting_values.notifications", 2);
        prefs.put("profile.default_content_setting_values.geolocation", 2);
        options.setExperimentalOption("prefs", prefs);

        // Рандомизация отпечатка (fingerprint)
        if (antiBotConfig != null && Boolean.TRUE.equals(antiBotConfig.getFingerprintRandomization())) {
            options.addArguments("--disable-webgl", "--disable-accelerated-2d-canvas",
                    "--disable-notifications", "--disable-extensions");
            options.addArguments("--lang=ru-RU,ru,en-US,en;q=0.9");
            options.addArguments("--timezone=" + getRandomTimezone());
        }

        return options;
    }

    private void configureDriver(final WebDriver driver) {
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(45));
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
    }

    private void applyAntiBotScripts(final WebDriver driver) {
        final JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");
        js.executeScript("Object.defineProperty(navigator, 'plugins', {get: () => [1,2,3,4,5]})");
        js.executeScript("Object.defineProperty(navigator, 'languages', {get: () => ['ru-RU','ru','en-US','en']})");
        js.executeScript("window.chrome = {runtime: {}};");
        // Дополнительные скрипты для маскировки
        js.executeScript("delete navigator.__webdriver_script_fn; delete document.__driver_evaluate;");
    }

    private boolean isCaptchaPage(final String url, final WebDriver driver) {
        if (url.contains("captcha") || url.contains("showcaptcha") || url.contains("challenge")) return true;
        try {
            final String title = driver.getTitle().toLowerCase(Locale.ROOT);
            final String body = driver.findElement(By.tagName("body")).getText().toLowerCase(Locale.ROOT);
            return title.contains("robot") || title.contains("капча") ||
                    body.contains("captcha") || body.contains("проверка") || body.contains("подтвердите, что вы человек");
        } catch (final Exception e) {
            return false;
        }
    }

    private boolean shouldRetryCaptcha() {
        // 30% шанс повторной попытки (чтобы не тратить ресурсы впустую)
        return random.nextDouble() < 0.3;
    }

    private String getRandomTimezone() {
        final String[] zones = {"Europe/Moscow", "Europe/Kiev", "Europe/Minsk", "UTC", "Europe/Warsaw"};
        return zones[random.nextInt(zones.length)];
    }

    private void waitForContent(final WebDriver driver) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(15)).until(d -> {
                try {
                    final String text = d.findElement(By.tagName("body")).getText();
                    if (text.toLowerCase().contains("captcha") || text.toLowerCase().contains("проверка")) return true;
                    return text != null && text.length() > 150;
                } catch (final Exception e) {
                    return false;
                }
            });
            Thread.sleep(random.nextInt(1000, 2501)); // Доп. пауза для динамического контента
        } catch (final Exception e) {
            log.debug("Content wait timeout, proceeding with available HTML");
        }
    }

    private Page createErrorPage(final Request request, final String error) {
        final Page page = new Page();
        page.setRequest(request);
        page.setStatusCode(500);
        page.setSkip(true);
        page.setRawText("ERROR: " + error);
        return page;
    }

    @Override
    public void setThread(final int threadNum) {
        // не использую
    }

    @Override
    public void close() {
        // не использую
    }
}
