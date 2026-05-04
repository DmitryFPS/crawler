package org.example.downloader;

import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.downloader.Downloader;
import us.codecraft.webmagic.selector.PlainText;

import java.time.Duration;

@Slf4j
public class SeleniumDownloader implements Downloader, AutoCloseable {

    private final ChromeOptions options;
    private final Duration waitTimeout;
    private final String contentSelector;

    public SeleniumDownloader() {
        this.waitTimeout = Duration.ofSeconds(15);
        this.contentSelector = "article, main, .content, [role='main'], .post-content";

        this.options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        options.setExperimentalOption("useAutomationExtension", false);
        options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
    }

    @Override
    public Page download(Request request, Task task) {
        WebDriver driver = null;
        try {
            driver = new ChromeDriver(options);
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));

            log.debug("Downloading with Selenium: {}", request.getUrl());
            driver.get(request.getUrl());

            // Ждём загрузки контента
            if (contentSelector != null && !contentSelector.isBlank()) {
                new WebDriverWait(driver, waitTimeout)
                        .until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(contentSelector)));
            } else {
                new WebDriverWait(driver, waitTimeout)
                        .until(d -> {
                            final String bodyText = d.findElement(By.tagName("body")).getText();
                            return bodyText != null && !bodyText.isBlank();
                        });
            }

            // Небольшая пауза для динамического контента
            Thread.sleep(1000);

            String html = driver.getPageSource();
            Page page = Page.ofSuccess(request);
            page.setRawText(html);
            page.setUrl(new PlainText(request.getUrl()));

            return page;

        } catch (Exception e) {
            log.error("Error downloading {}: {}", request.getUrl(), e.getMessage());
            Page page = new Page();
            page.setRequest(request);
            page.setStatusCode(500);
            page.setSkip(true);
            return page;
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    @Override
    public void setThread(int threadNum) {
        // no-op for single-use driver
    }

    @Override
    public void close() {
        // no-op for single-use driver
    }
}
