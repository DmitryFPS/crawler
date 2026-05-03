package org.example;

import lombok.Getter;
import lombok.Setter;

@Getter
public class PageData {

    @Setter
    private String url;
    private String title;
    private String h1;
    private String description;

    @Setter
    private double score;
    @Setter
    private int keywordMatches;

    public PageData() {
    }

    public PageData(String url,
                    String title,
                    String h1,
                    String description,
                    double score,
                    int keywordMatches) {
        this.url = url;
        this.title = title;
        this.h1 = h1;
        this.description = description;
        this.score = score;
        this.keywordMatches = keywordMatches;
    }

    // --- getters/setters ---

    public void setTitle(String title) {
        this.title = safe(title);
    }

    public void setH1(String h1) {
        this.h1 = safe(h1);
    }

    public void setDescription(String description) {
        this.description = safe(description);
    }

    // --- helpers ---

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
