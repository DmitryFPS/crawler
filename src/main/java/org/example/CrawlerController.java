package org.example;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/crawler")
@RequiredArgsConstructor
public class CrawlerController {

    private final CrawlerService service;

    @PostMapping("/start")
    public Map<String, String> start(@RequestBody CrawlRequest request) {
        return Map.of("jobId", service.start(request));
    }

    @PostMapping("/stop/{id}")
    public ResponseEntity<Map<String, String>> stop(@PathVariable String id) {
        service.stop(id);
        return ResponseEntity.ok(Map.of("status", "stopped"));
    }

    @GetMapping("/status/{id}")
    public ResponseEntity<Map<String, String>> status(@PathVariable String id) {
        CrawlerService.CrawlStatus status = service.getStatus(id);
        if (status == CrawlerService.CrawlStatus.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("status", status.name().toLowerCase()));
    }
}
