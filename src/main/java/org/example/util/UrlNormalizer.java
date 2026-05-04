package org.example.util;

public class UrlNormalizer {

    public static String normalize(final String url) {
        if (url == null) {
            return null;
        }

        String normalized = url
                .trim()
                .replaceAll("#.*$", "")           // убрать якорь
                .replaceAll("\\?.*$", "")         // убрать query params (опционально)
                .replaceAll("/+$", "")            // убрать trailing slash
                .toLowerCase();

        // Добавить схему если нет
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }

        return normalized;
    }
}
