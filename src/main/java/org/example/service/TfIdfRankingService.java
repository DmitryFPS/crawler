package org.example.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TfIdfRankingService implements RankingService {

    @Override
    public double calculateScore(final String content,
                                 final String title,
                                 final String h1,
                                 final List<String> keywords) {
        if (keywords == null || keywords.isEmpty() || content == null) {
            return 0;
        }

        final String contentLower = safeLower(content);
        final String titleLower = safeLower(title);
        final String h1Lower = safeLower(h1);

        double totalScore = 0;

        for (String keyword : keywords) {
            final String kw = keyword.toLowerCase(Locale.ROOT);
            if (kw.isBlank()) {
                continue;
            }

            final int tf = countTermFrequency(contentLower, kw);
            if (tf == 0) {
                continue;
            }

            final double idf = calculateIdf(contentLower, kw);
            final double boost = calculateBoost(titleLower, h1Lower, kw);

            totalScore += tf * idf * boost;
        }

        return normalize(totalScore, contentLower.length());
    }

    private int countTermFrequency(final String text, final String term) {
        // Используем границы слов для точного совпадения
        final String regex = "\\b" + Pattern.quote(term.toLowerCase()) + "\\b";
        final Pattern pattern = Pattern.compile(regex);
        final Matcher matcher = pattern.matcher(text.toLowerCase());

        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private double calculateIdf(final String text,
                                final String term) {
        final int termLen = term.length();
        final int textLen = text.length();

        if (termLen == 0 || textLen == 0) {
            return 0;
        }

        return Math.log(1.0 + (double) textLen / (termLen + 1));
    }

    private double calculateBoost(final String title,
                                  final String h1,
                                  final String keyword) {
        double boost = 1.0;

        // Сильный буст за заголовок страницы
        if (title != null && title.toLowerCase().contains(keyword)) {
            boost *= 5.0;
        }
        // Буст за H1
        if (h1 != null && h1.toLowerCase().contains(keyword)) {
            boost *= 3.0;
        }

        return boost;
    }

    private double normalize(final double score,
                             final int contentLength) {
        return score / Math.sqrt(contentLength + 1);
    }

    private String safeLower(final String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
