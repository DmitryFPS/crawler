package org.example.util;

import java.net.MalformedURLException;
import java.net.URL;

public class DomainUtils {

    public static String extractDomain(final String url) {
        try {
            final URL parsed = new URL(url);
            final String host = parsed.getHost();

            if (host == null) {
                return null;
            }

            // Убираем www.
            return host.replaceFirst("^www\\.", "");
        } catch (final MalformedURLException e) {
            return null;
        }
    }

    public static boolean isSameDomain(final String url1,
                                       final String url2) {

        final String d1 = extractDomain(url1);
        final String d2 = extractDomain(url2);

        return d1 != null && d1.equals(d2);
    }
}
