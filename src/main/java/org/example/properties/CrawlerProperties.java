package org.example.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@ConfigurationProperties(prefix = "crawler")
@Getter
@Setter
public class CrawlerProperties {
    private int threads = 10;
    private int timeout = 10000;
    private int sleep = 1000;
    private int maxDepth = 100;
    private int maxPagesPerJob = 1000;

    private Retry retry = new Retry();
    private RateLimit rateLimit = new RateLimit();
    private Filter filter = new Filter();
    private AntiBot antiBot = new AntiBot();
    private Selenium selenium = new Selenium();


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
        private boolean allowCrossDomain = false;
        private int minContentLength = 200;
        private Set<String> allowedDomains = Set.of();
        private Set<String> blockedDomains = Set.of();
    }

    @Getter
    @Setter
    public static class AntiBot {
        private RandomDelay randomDelay = new RandomDelay();
        private Boolean userAgentRotation = true;
        private Boolean fingerprintRandomization = true;

        @Getter
        @Setter
        public static class RandomDelay {
            private Integer min = 1000;
            private Integer max = 4000;
        }
    }

    @Getter
    @Setter
    public static class Selenium {
        private Hub hub = new Hub();
        private Chrome chrome = new Chrome();
        private Proxy proxy = new Proxy();

        @Getter
        @Setter
        public static class Hub {
            private String url;
        }

        @Getter
        @Setter
        public static class Chrome {
            private String options;
        }

        @Getter
        @Setter
        public static class Proxy {
            private Boolean enabled = false;
            private String list;
        }
    }
}
