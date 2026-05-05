package org.example.downloader;

import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
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
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class SeleniumDownloader implements Downloader, AutoCloseable {

    private final String seleniumHubUrl;
    private final Duration pageLoadTimeout = Duration.ofSeconds(120);

    public SeleniumDownloader(final String hubUrl) {
        // Проверка URL
        this.seleniumHubUrl = hubUrl != null && !hubUrl.isBlank()
                ? hubUrl
                : "http://selenium:4444/wd/hub";

        log.info("SeleniumDownloader initialized with hub: {}", this.seleniumHubUrl);
    }

    @Override
    public Page download(final Request request,
                         final Task task) {
        // Проверка URL страницы
        String pageUrl = request.getUrl();
        if (pageUrl == null || pageUrl.isBlank()) {
            log.error("Request URL is null or empty");
            return createErrorPage(request, "Empty URL");
        }

        WebDriver driver = null;
        try {
            log.debug("Downloading with Selenium: {} (hub: {})", pageUrl, seleniumHubUrl);

            // Настройки Chrome
            ChromeOptions options = new ChromeOptions();

            options.addArguments("--headless=new");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--disable-gpu");
            options.addArguments("--window-size=1920,1080");

            options.addArguments("--disable-blink-features=AutomationControlled");

            options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
            options.setExperimentalOption("useAutomationExtension", false);

            // ОБХОД ДЕТЕКТОВ АВТОМАТИЗАЦИИ:
            String[] userAgents = {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15"
            };
            options.addArguments("--user-agent=" + userAgents[new java.util.Random().nextInt(userAgents.length)]);

            options.addArguments("--disable-webgl");
            options.addArguments("--disable-accelerated-2d-canvas");
            options.addArguments("--disable-notifications");
            options.addArguments("--disable-popup-blocking");
            options.addArguments("--disable-extensions");
            options.addArguments("--lang=ru-RU,ru,en-US,en;q=0.9");
            options.addArguments("--timezone=Europe/Moscow");
            options.setPageLoadStrategy(PageLoadStrategy.EAGER);

            // Настройки приватности
            Map<String, Object> prefs = new HashMap<>();
            prefs.put("credentials_enable_service", false);
            prefs.put("profile.password_manager_enabled", false);
            prefs.put("profile.default_content_setting_values.notifications", 2);
            prefs.put("profile.default_content_setting_values.geolocation", 2);
            options.setExperimentalOption("prefs", prefs);

            // Подключение к Selenium Hub с проверкой
            final URL hubUrl = new URL(seleniumHubUrl);

            driver = new RemoteWebDriver(hubUrl, options);
            driver.manage().timeouts().pageLoadTimeout(pageLoadTimeout);
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(60));
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));

            // Скрипты для маскировки автоматизации
            final JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");
            js.executeScript("Object.defineProperty(navigator, 'plugins', {get: () => [1,2,3,4,5]})");
            js.executeScript("Object.defineProperty(navigator, 'languages', {get: () => ['ru-RU', 'ru', 'en-US', 'en']})");
            js.executeScript("window.chrome = {runtime: {}};");
            js.executeScript("delete navigator.__webdriver_script_fn");
            js.executeScript("delete document.__driver_evaluate");
            js.executeScript("delete document.__webdriver_evaluate");
            js.executeScript("delete document.__fxdriver_evaluate");

            // Добавляем случайную задержку перед загрузкой (имитация человека)
            Thread.sleep(new java.util.Random().nextInt(1000) + 500);

            // Переход с обработкой редиректов
            log.debug("Navigating to: {}", pageUrl);
            driver.get(pageUrl);
            String finalUrl = driver.getCurrentUrl();
            if (!finalUrl.equals(pageUrl)) {
                log.debug("Redirect: {} -> {}", pageUrl, finalUrl);
                // Проверка: если редирект на CAPTCHA — помечаем
                if (finalUrl.contains("captcha") || finalUrl.contains("showcaptcha") ||
                        driver.getTitle().toLowerCase().contains("robot") || driver.getTitle().toLowerCase().contains("капча")) {
                    log.warn("CAPTCHA detected at: {}", finalUrl);
                    return createErrorPage(request, "CAPTCHA_BLOCKED");
                }
            }

            // Ожидание контента
            waitForContent(driver);

            final String html = driver.getPageSource();
            log.debug("Successfully downloaded: {} ({} chars)", pageUrl, html != null ? html.length() : 0);

            final Page page = new Page();
            page.setRequest(request);
            page.setRawText(html);
            page.setUrl(new PlainText(pageUrl));

            page.setStatusCode(200);
            page.setDownloadSuccess(true);

            log.debug("Successfully downloaded: {} ({} chars)", pageUrl, html != null ? html.length() : 0);
            return page;

        } catch (final Exception e) {
            log.error("Error downloading {}: {} (cause: {})", pageUrl, e.getMessage(),
                    e.getCause() != null ? e.getCause().getMessage() : "null", e);

            return createErrorPage(request, e.getMessage());
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (final Exception e) {
                    log.warn("Error closing WebDriver for {}: {}", pageUrl, e.getMessage());
                }
            }
        }
    }

    @Override
    public void setThread(int threadNum) {

    }

    // === Улучшенный waitForContent ===
    private void waitForContent(WebDriver driver) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(15))
                    .until(d -> {
                        try {
                            // Ждём не только body, но и появления контента
                            WebElement body = d.findElement(By.tagName("body"));
                            String text = body.getText();
                            // Проверяем, что это не страница с капчей
                            if (text.toLowerCase().contains("captcha") ||
                                    text.toLowerCase().contains("проверка") ||
                                    text.toLowerCase().contains("robot")) {
                                return true; // Завершаем, чтобы обработать как ошибку
                            }
                            return text != null && text.length() > 150;
                        } catch (Exception e) {
                            return false;
                        }
                    });

            // Дополнительная пауза для динамического контента (React, Vue)
            Thread.sleep(new java.util.Random().nextInt(1500) + 1000);

            // Пробуем дождаться конкретных элементов, если известны
            try {
                new WebDriverWait(driver, Duration.ofSeconds(3))
                        .until(d ->
                                d.findElements(By.cssSelector("article, .content, .post, [itemprop='articleBody']"))
                                        .size() > 0
                        );

            } catch (TimeoutException ignored) {
                // Не критично, продолжаем
            }

            log.debug("Content loaded successfully");
        } catch (final TimeoutException e) {
            log.debug("Content wait timeout for {}, proceeding with available HTML", driver.getCurrentUrl());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for content");
        }
    }

    private Page createErrorPage(Request request, String errorMessage) {
        Page page = new Page();
        page.setRequest(request);
        page.setStatusCode(500);
        page.setSkip(true);
        page.setRawText("ERROR: " + errorMessage);
        return page;
    }

    @Override
    public void close() {
        // no-op
    }
}
