package org.example.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "pages", indexes = {
        @Index(name = "idx_pages_score", columnList = "score DESC"),
        @Index(name = "idx_pages_crawled_at", columnList = "crawled_at DESC"),
        @Index(name = "idx_pages_domain", columnList = "domain"),
        @Index(name = "idx_pages_status", columnList = "status")
})
@Getter
@Setter
public class PageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, columnDefinition = "TEXT")
    private String url;

    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String h1;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String contentText;

    // Это поле заполняется триггером PostgreSQL, JPA его не пишет
    @Column(columnDefinition = "tsvector", insertable = false, updatable = false)
    private String searchVector;

    private Double score = 0.0;
    private Integer keywordMatches = 0;

    private String domain;
    private Integer crawlDepth = 0;
    private String status = "new";

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Instant crawledAt;
    private Instant updatedAt;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
