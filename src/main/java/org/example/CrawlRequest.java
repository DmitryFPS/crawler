package org.example;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class CrawlRequest {
    private String url;
    private int threads;
    private List<String> keywords;
}
