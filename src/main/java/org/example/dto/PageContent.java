package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PageContent {
    private String url;
    private String title = "";
    private String h1 = "";
    private String description = "";
    private String contentText = "";
    private String domain;
    private double score;
    private int keywordMatches;
    private int crawlDepth;
    private String status = "new";
}
