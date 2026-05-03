package org.example;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class MetricsService {

    private final Counter processed;
    private final Counter failed;
    private final Counter retries;
    private final Timer processingTime;

    public MetricsService(MeterRegistry registry) {
        this.processed = registry.counter("crawler.pages.processed");
        this.failed = registry.counter("crawler.pages.failed");
        this.retries = registry.counter("crawler.pages.retries");
        this.processingTime = registry.timer("crawler.page.processing.time");
    }

    public void processed() {
        processed.increment();
    }

    public void failed() {
        failed.increment();
    }

    public void retry() {
        retries.increment();
    }

    public Timer timer() {
        return processingTime;
    }
}
