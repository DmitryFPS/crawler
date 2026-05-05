package org.example.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crawler")
@Getter
@Setter
public class CrawlerProperties {
    private int threads = 10;
    private int timeout = 10000;
    private int sleep = 1000;
    private int maxDepth = 10;
    private Retry retry = new Retry();
    private RateLimit rateLimit = new RateLimit();
    private Filter filter = new Filter();

    @Getter
    @Setter
    public static class Retry {
        private int maxAttempts = 3;
        private int sleep = 3000;
        private boolean exponential = true;
    }

    @Getter
    @Setter
    public static class RateLimit {
        private boolean enabled = true;
        private int requestsPerMinute = 30;
    }

    @Getter
    @Setter
    public static class Filter {
        private boolean urlsByKeywords = false;
        private int minContentLength = 200;
    }
}
