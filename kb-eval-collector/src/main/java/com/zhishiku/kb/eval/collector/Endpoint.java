package com.zhishiku.kb.eval.collector;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

final class Endpoint {
    private Endpoint() {
    }

    static URI validate(String value, boolean allowHttpLoopback) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            String path = uri.getRawPath();
            if (host == null || scheme == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || path == null || path.contains("\\") || path.contains("//")
                    || path.toLowerCase(Locale.ROOT).matches(".*%(2e|2f|5c|25).*")) {
                throw invalid();
            }
            for (String part : path.split("/")) {
                if (part.equals(".") || part.equals("..")) {
                    throw invalid();
                }
            }
            boolean secure = scheme.equalsIgnoreCase("https");
            boolean local = scheme.equalsIgnoreCase("http") && allowHttpLoopback
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(host.toLowerCase(Locale.ROOT));
            if (!secure && !local) {
                throw invalid();
            }
            return uri;
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw invalid();
        }
    }

    static URI api(URI base, String suffix) {
        String value = base.toASCIIString();
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return URI.create(value + suffix);
    }

    private static CollectionException invalid() {
        return new CollectionException(CollectionException.Code.CONFIG, "config", 0, "baseUrl");
    }
}
