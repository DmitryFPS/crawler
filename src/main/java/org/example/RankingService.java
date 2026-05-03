package org.example;

import java.util.List;

public interface RankingService {
    double calculateScore(String text, String title, String h1, List<String> keywords);
}
