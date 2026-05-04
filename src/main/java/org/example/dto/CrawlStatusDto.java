package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CrawlStatusDto {
    private String jobId;
    private String status;
    private Long processedCount;
}
