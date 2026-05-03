package org.example;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crawler")
@Getter
@Setter
public class CrawlerProperties {
    private int threads;
    private int timeout;
    private int sleep;
    private Retry retry;

    @Getter
    @Setter
    public static class Retry {
        private int maxAttempts;
        private int sleep;
    }
}
