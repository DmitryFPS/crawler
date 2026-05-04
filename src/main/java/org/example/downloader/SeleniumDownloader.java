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

            // 🔽 Настройки Chrome
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--disable-gpu");
            options.addArguments("--window-size=1920,1080");
            options.addArguments("--disable-blink-features=AutomationControlled");
            options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
            options.setExperimentalOption("useAutomationExtension", false);

            // 🔽 ОБХОД ДЕТЕКТОВ АВТОМАТИЗАЦИИ:
            options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            options.addArguments("--lang=ru-RU,ru,en;q=0.9");
            options.addArguments("--disable-webgl");
            options.addArguments("--disable-accelerated-2d-canvas");
            options.addArguments("--disable-notifications");
            options.addArguments("--disable-popup-blocking");
            options.addArguments("--disable-extensions");
            options.setPageLoadStrategy(PageLoadStrategy.EAGER);

            // Настройки приватности
            Map<String, Object> prefs = new HashMap<>();
            prefs.put("credentials_enable_service", false);
            prefs.put("profile.password_manager_enabled", false);
            options.setExperimentalOption("prefs", prefs);

            // Подключение к Selenium Hub с проверкой
            final URL hubUrl = new URL(seleniumHubUrl);
            driver = new RemoteWebDriver(hubUrl, options);
            driver.manage().timeouts().pageLoadTimeout(pageLoadTimeout);
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(60));
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));

            // СКРИПТЫ ДЛЯ ОБХОДА ДЕТЕКТОВ:
            final JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");
            js.executeScript("Object.defineProperty(navigator, 'plugins', {get: () => [1,2,3,4,5]})");
            js.executeScript("window.chrome = {runtime: {}};");

            // Переход на страницу
            log.debug("Navigating to: {}", pageUrl);
            driver.get(pageUrl);
            log.info("Page loaded: {} (title: {})", driver.getCurrentUrl(), driver.getTitle());

            // Ожидание контента
            waitForContent(driver);

            // Пауза для динамического контента
            Thread.sleep(2000);

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

    private void waitForContent(WebDriver driver) {
        try {
            // 🔽 Ждать только появления body с минимальным текстом (не сложные селекторы!)
            new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(d -> {
                        try {
                            String bodyText = d.findElement(By.tagName("body")).getText();
                            return bodyText != null && bodyText.length() > 100; // Минимум 100 символов
                        } catch (Exception e) {
                            return false;
                        }
                    });
            log.debug("Content loaded successfully");
        } catch (final TimeoutException e) {
            log.debug("Content wait timeout for {}, proceeding with available HTML",
                    driver.getCurrentUrl());
            // НЕ выбрасываем ошибку — продолжаем с тем, что есть
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
