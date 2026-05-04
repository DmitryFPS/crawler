package org.example.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class MetricsService {

    private final Counter processed;
    private final Counter failed;
    private final Counter retries;
    private final Counter skippedByKeyword;
    private final Timer processingTime;

    public MetricsService(final MeterRegistry registry) {
        this.processed = registry.counter("crawler.pages.processed");
        this.failed = registry.counter("crawler.pages.failed");
        this.retries = registry.counter("crawler.pages.retries");
        this.skippedByKeyword = registry.counter("crawler.pages.skipped_by_keyword");
        this.processingTime = registry.timer("crawler.page.processing.time");
    }

    public void pageProcessed() {
        processed.increment();
    }

    public void pageFailed() {
        failed.increment();
    }

    public void pageRetried() {
        retries.increment();
    }

    public void pageSkippedByKeyword() {
        skippedByKeyword.increment();
    }

    public Timer getProcessingTimer() {
        return processingTime;
    }
}
