package org.example.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultDto {
    private String url;
    private String title;
    private String description;
    private Double score;
    private Integer keywordMatches;
    private Instant crawledAt;
    private String domain;
    private String contentText;
    private String fullContent;
}
