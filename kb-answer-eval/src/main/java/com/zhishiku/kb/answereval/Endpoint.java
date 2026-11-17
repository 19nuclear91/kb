package com.zhishiku.kb.answereval;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/** 固定根地址；仅显式本机开发场景允许 HTTP。 */
public final class Endpoint {
    private Endpoint() {
    }

    public static URI validate(String value, boolean allowHttpLoopback) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            String path = uri.getRawPath();
            String scheme = uri.getScheme();
            if (host == null || scheme == null || path == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || path.indexOf(92) >= 0 || path.contains("//")
                    || path.toLowerCase(Locale.ROOT).matches(".*%(2e|2f|5c|25).*")) {
                throw invalid();
            }
            for (String segment : path.split("/")) {
                if (".".equals(segment) || "..".equals(segment)) {
                    throw invalid();
                }
            }
            if (!"https".equalsIgnoreCase(scheme) && !("http".equalsIgnoreCase(scheme) && allowHttpLoopback
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(host.toLowerCase(Locale.ROOT)))) {
                throw invalid();
            }
            return uri;
        } catch (URISyntaxException | IllegalArgumentException | NullPointerException failure) {
            throw invalid();
        }
    }

    public static URI api(URI base, String suffix) {
        String prefix = base.toASCIIString();
        return URI.create((prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix) + suffix);
    }

    private static EvaluationFailure invalid() {
        return new EvaluationFailure(EvaluationFailure.Code.CONFIGURATION);
    }
}
