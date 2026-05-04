package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.dto.CrawlRequest;
import org.example.dto.CrawlStatusDto;
import org.example.service.CrawlerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/crawler")
@RequiredArgsConstructor
public class CrawlerController {

    private final CrawlerService crawlerService;

    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> start(@RequestBody final CrawlRequest request) {
        final String jobId = crawlerService.start(request);
        return ResponseEntity.ok(Map.of("jobId", jobId));
    }

    @PostMapping("/stop/{id}")
    public ResponseEntity<Map<String, String>> stop(@PathVariable final String id) {
        crawlerService.stop(id);
        return ResponseEntity.ok(Map.of("status", "stopped"));
    }

    @GetMapping("/status/{id}")
    public ResponseEntity<CrawlStatusDto> status(@PathVariable final String id) {
        return crawlerService.getStatus(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
