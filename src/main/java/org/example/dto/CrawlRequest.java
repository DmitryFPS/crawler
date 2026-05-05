package org.example.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class CrawlRequest {
    private String url;
    private List<String> seedUrls;
    private List<String> keywords;
    private Integer threads;
    private Integer maxDepth;
}
