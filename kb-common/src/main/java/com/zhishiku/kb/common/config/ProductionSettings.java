package com.zhishiku.kb.common.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 在创建数据库连接和执行迁移前检查有效配置；错误只包含配置名，不包含密钥。
 */
public final class ProductionSettings {
    private ProductionSettings() {
    }

    public static void validate(String module, Set<String> profiles, Map<String, String> settings) {
        if (profiles.contains("dev")) {
            if (profiles.contains("prod")) {
                fail("dev 与 prod 不得同时启用");
            }
            return;
        }
        validateTelemetry(module, settings);
        if (module.equals("kb-app") || module.equals("kb-worker")) {
            require(settings, "spring.datasource.url", "spring.datasource.username",
                    "spring.datasource.password", "kb.storage.endpoint",
                    "kb.storage.access-key", "kb.storage.secret-key", "kb.storage.bucket");
            rejectExample(settings, "spring.datasource.password", "kb_dev_pwd");
            rejectExample(settings, "kb.storage.secret-key", "kbminio_dev_pwd");
        }
        if (!module.equals("kb-worker")) require(settings, "spring.data.redis.host");
        if (module.equals("kb-app")) {
            if (Boolean.parseBoolean(settings.get("kb.dev-auth.enabled"))) fail("生产禁止 kb.dev-auth.enabled");
            if (!"classpath:db/migration".equals(settings.get("spring.flyway.locations")))
                fail("生产 spring.flyway.locations 只能包含 classpath:db/migration");
            if (Boolean.parseBoolean(settings.get("spring.flyway.baseline-on-migrate")))
                fail("生产禁止自动基线化未知数据库");
        }
        if (module.equals("kb-ai")) {
            require(settings, "kb.ai.app-base-url");
            if (Boolean.parseBoolean(settings.get("kb.ai.audit-in-memory-enabled")))
                fail("生产禁止内存 AI 审计");
        }
        if (module.equals("kb-gateway")) {
            require(settings, "spring.cloud.gateway.routes[0].uri", "spring.cloud.gateway.routes[1].uri");
            boolean originsPresent = false;
            for (var entry : settings.entrySet()) {
                String key = entry.getKey().toLowerCase().replace("-", "");
                if (!key.contains("globalcors") || !key.contains("allowedorigin")) continue;
                String value = entry.getValue();
                if (value == null || value.isBlank()) continue;
                if (key.contains("patterns")) fail("生产 CORS 必须使用明确 allowedOrigins");
                originsPresent = true;
                for (String origin : value.split(",")) {
                    try {
                        URI uri = URI.create(origin.trim());
                        if (origin.contains("*") || uri.getHost() == null || uri.getUserInfo() != null
                                || !Set.of("http", "https").contains(uri.getScheme())
                                || (uri.getPath() != null && !uri.getPath().isEmpty())
                                || uri.getQuery() != null || uri.getFragment() != null)
                            fail("生产 CORS origin 必须是完整且无通配的来源");
                    } catch (IllegalArgumentException e) {
                        fail("生产 CORS origin 配置无效");
                    }
                }
            }
            if (!originsPresent) fail("生产必须配置 CORS allowedOrigins");
        }
        for (var entry : settings.entrySet()) {
            if (entry.getKey().startsWith("logging.level.")
                    && ("DEBUG".equalsIgnoreCase(entry.getValue()) || "TRACE".equalsIgnoreCase(entry.getValue())))
                fail("生产禁止详细日志：" + entry.getKey());
            if ("sa-token.is-log".equals(entry.getKey()) && Boolean.parseBoolean(entry.getValue()))
                fail("生产禁止 Sa-Token 调试日志");
        }
    }

    private static void require(Map<String, String> settings, String... keys) {
        for (String key : keys) {
            String value = settings.get(key);
            if (value == null || value.isBlank() || value.contains("${")) fail("生产缺少配置：" + key);
        }
    }

    private static void validateTelemetry(String module, Map<String, String> settings) {
        require(settings, "management.server.port", "management.server.address");
        int port;
        try {
            port = Integer.parseInt(settings.get("management.server.port").trim());
        } catch (RuntimeException error) {
            throw new IllegalStateException("management.server.port");
        }
        String businessPort = settings.get("server.port");
        if (businessPort == null || businessPort.isBlank()) {
            businessPort = switch (module) {
                case "kb-gateway" -> "8080";
                case "kb-app" -> "8081";
                case "kb-ai" -> "8082";
                case "kb-worker" -> "8083";
                default -> "8080";
            };
        }
        int effectiveBusinessPort;
        try {
            effectiveBusinessPort = Integer.parseInt(businessPort.trim());
        } catch (RuntimeException error) {
            throw new IllegalStateException("server.port");
        }
        if (port <= 0 || port > 65535 || port == effectiveBusinessPort) {
            fail("management.server.port");
        }
        String address = settings.get("management.server.address").trim();
        if ("*".equals(address)) {
            fail("management.server.address");
        }
        // 只解析数字地址，不为主机名触发 DNS；覆盖 IPv6 压缩、括号及 IPv4 等价写法。
        if (address.contains(":") || address.matches("[0-9.]+")) {
            try {
                if (InetAddress.getByName(address).isAnyLocalAddress()) {
                    fail("management.server.address");
                }
            } catch (UnknownHostException error) {
                throw new IllegalStateException("management.server.address");
            }
        }
        for (var entry : settings.entrySet()) {
            if (entry.getKey().startsWith("management.endpoints.web.exposure.include")
                    && entry.getValue() != null && entry.getValue().contains("*")) {
                fail("management.endpoints.web.exposure.include");
            }
        }
        if (Boolean.parseBoolean(settings.get("kb.telemetry.tracing.enabled"))
                || Boolean.parseBoolean(settings.get("management.tracing.enabled"))) {
            String key = "kb.telemetry.tracing.otlp-endpoint";
            String endpoint = settings.get(key);
            if (endpoint == null || endpoint.isBlank()) {
                key = "management.otlp.tracing.endpoint";
                endpoint = settings.get(key);
            }
            validateTelemetryEndpoint(key, endpoint);
            for (String samplingKey : List.of("kb.telemetry.tracing.sampling-probability",
                    "management.tracing.sampling.probability")) {
                String value = settings.get(samplingKey);
                if (value != null) {
                    try {
                        double probability = Double.parseDouble(value);
                        if (!Double.isFinite(probability) || probability <= 0 || probability > 1) {
                            fail(samplingKey);
                        }
                    } catch (RuntimeException error) {
                        throw new IllegalStateException(samplingKey);
                    }
                }
            }
        }
        Map<String, TreeMap<Integer, String>> indexedBuckets = new TreeMap<>();
        for (var entry : settings.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("kb.telemetry.histograms.") && entry.getValue() != null) {
                int bracket = key.indexOf('[');
                if (bracket < 0) {
                    histogramSeconds(key, entry.getValue());
                } else {
                    try {
                        int index = Integer.parseInt(key.substring(bracket + 1, key.length() - 1));
                        indexedBuckets.computeIfAbsent(key.substring(0, bracket), ignored -> new TreeMap<>())
                                .put(index, entry.getValue());
                    } catch (RuntimeException error) {
                        throw new IllegalStateException(key.substring(0, bracket));
                    }
                }
            }
        }
        indexedBuckets.forEach((key, values) -> histogramSeconds(key, String.join(",", values.values())));
    }

    private static void validateTelemetryEndpoint(String key, String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && internalHost(host)))) {
                fail(key);
            }
        } catch (RuntimeException error) {
            throw new IllegalStateException(key);
        }
    }

    private static boolean internalHost(String host) {
        if (host.equals("localhost") || host.equals("[::1]") || (!host.contains(".") && !host.contains(":"))) {
            return true;
        }
        if (host.endsWith(".svc") || host.endsWith(".svc.cluster.local")) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int[] octets = new int[4];
            for (int i = 0; i < parts.length; i++) {
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] < 0 || octets[i] > 255) {
                    return false;
                }
            }
            return octets[0] == 10 || octets[0] == 127 || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                    || (octets[0] == 192 && octets[1] == 168);
        } catch (NumberFormatException error) {
            return false;
        }
    }

    /** 分桶统一转换为秒，允许秒数、ms/s/m/h 后缀与 ISO-8601 Duration。 */
    public static double[] histogramSeconds(String key, String value) {
        try {
            var buckets = new ArrayList<Double>();
            double previous = 0;
            for (String item : value.split(",", -1)) {
                String bucket = item.trim();
                double seconds;
                if (bucket.startsWith("P")) {
                    seconds = Duration.parse(bucket).toNanos() / 1_000_000_000.0;
                } else {
                    double factor = 1;
                    if (bucket.endsWith("ms")) {
                        factor = 0.001;
                        bucket = bucket.substring(0, bucket.length() - 2);
                    } else if (bucket.endsWith("s") || bucket.endsWith("m") || bucket.endsWith("h")) {
                        factor = bucket.endsWith("m") ? 60 : bucket.endsWith("h") ? 3600 : 1;
                        bucket = bucket.substring(0, bucket.length() - 1);
                    }
                    seconds = Double.parseDouble(bucket) * factor;
                }
                if (!Double.isFinite(seconds) || seconds <= previous || seconds * 1_000_000_000 > Long.MAX_VALUE) {
                    fail(key);
                }
                buckets.add(seconds);
                previous = seconds;
            }
            return buckets.stream().mapToDouble(Double::doubleValue).toArray();
        } catch (RuntimeException error) {
            throw new IllegalStateException(key);
        }
    }

    private static void rejectExample(Map<String, String> settings, String key, String example) {
        if (example.equals(settings.get(key))) fail("生产不能使用示例凭据：" + key);
    }

    private static void fail(String message) {
        throw new IllegalStateException(message);
    }
}
