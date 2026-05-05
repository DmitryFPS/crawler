package org.example.util;

import java.net.MalformedURLException;
import java.net.URL;

public class DomainUtils {
    public static String extractDomain(final String url) {
        try {
            final URL parsed = new URL(url);
            final String host = parsed.getHost();
            if (host == null) return null;
            return host.replaceFirst("^www\\.", "").toLowerCase();
        } catch (final MalformedURLException e) {
            return null;
        }
    }
}
