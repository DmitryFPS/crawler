package org.example;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Primary
public class RankingServiceImpl implements RankingService {

    @Override
    public double calculateScore(String text,
                                 String title,
                                 String h1,
                                 List<String> keywords) {

        if (keywords == null || keywords.isEmpty()) {
            return 0;
        }

        text = safeLower(text);
        title = safeLower(title);
        h1 = safeLower(h1);

        double score = 0;

        for (String keyword : keywords) {

            String k = keyword.toLowerCase();

            int tf = countOccurrences(text, k);

            if (tf == 0) continue;

            double idf = calculateIdf(text, k);

            double boost = calculateBoost(title, h1, k);

            score += tf * idf * boost;
        }

        // нормализация (чтобы длинные страницы не выигрывали всегда)
        return normalize(score, text);
    }

    // -----------------------
    // TF
    // -----------------------
    private int countOccurrences(String text, String keyword) {
        int count = 0;
        int idx = 0;

        while ((idx = text.indexOf(keyword, idx)) != -1) {
            count++;
            idx += keyword.length();
        }

        return count;
    }

    // -----------------------
    // IDF (упрощённый)
    // -----------------------
    private double calculateIdf(String text, String keyword) {
        return Math.log(1 + (double) text.length() / (keyword.length() + 1));
    }

    // -----------------------
    // Boost (важные зоны страницы)
    // -----------------------
    private double calculateBoost(String title, String h1, String keyword) {

        double boost = 1.0;

        if (title != null && title.contains(keyword)) {
            boost *= 3.0;
        }

        if (h1 != null && h1.contains(keyword)) {
            boost *= 2.0;
        }

        return boost;
    }

    // -----------------------
    // Нормализация
    // -----------------------
    private double normalize(double score, String text) {
        return score / Math.sqrt(text.length() + 1);
    }

    private String safeLower(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}