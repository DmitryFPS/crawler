package org.example.service;

import java.util.List;

public interface RankingService {
    double calculateScore(final String content,
                          final String title,
                          final String h1,
                          final List<String> keywords);
}
