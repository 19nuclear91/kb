package com.zhishiku.notifier;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;

final class NotifierSettings {
    final String environment;
    final String deployment;
    final URI grafanaRoot;
    final URI adminRoot;
    final String address;
    final String managementAddress;
    final int port;
    final int managementPort;
    final AlertCatalog catalog;
    private final byte[] bearerHash;
    private final String token;
    private final String signingSecret;

    NotifierSettings(NotifierProperties properties) {
        environment = identity(properties.environment(), "environment");
        deployment = identity(properties.deployment(), "deployment");
        grafanaRoot = root(properties.grafanaRoot(), "grafana-root");
        adminRoot = root(properties.adminRoot(), "admin-root");
        address = address(properties.address(), "address");
        managementAddress = address(properties.managementAddress(), "management-address");
        port = port(properties.port(), 8090, "port");
        managementPort = port(properties.managementPort(), 9090, "management-port");
        if (port == managementPort) {
            throw config("management-port");
        }
        String bearer = secret(properties.bearerSecretFile(), "bearer-secret-file", 32, 512);
        bearerHash = hash(bearer);
        token = secret(properties.dingtalkTokenFile(), "dingtalk-token-file", 20, 256);
        signingSecret = secret(properties.dingtalkSecretFile(), "dingtalk-secret-file", 16, 256);
        if (!token.matches("[A-Za-z0-9_-]+") || !signingSecret.matches("[A-Za-z0-9_-]+")) {
            throw config("robot-credentials");
        }
        try (InputStream input = openFile(properties.catalogFile(), "catalog-file")) {
            catalog = AlertCatalog.parse(BoundedJson.read(input, 64 * 1024));
        } catch (Exception exception) {
            throw config("catalog-file");
        }
    }

    boolean authenticates(String authorization) {
        String candidate = "";
        if (authorization != null && authorization.startsWith("Bearer ") && authorization.length() <= 1024) {
            candidate = authorization.substring(7);
        }
        return MessageDigest.isEqual(bearerHash, hash(candidate));
    }

    String token() {
        return token;
    }

    String signingSecret() {
        return signingSecret;
    }

    private static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw config("cryptography");
        }
    }

    private static String secret(String file, String name, int minimum, int maximum) {
        try (InputStream input = openFile(file, name)) {
            String value = BoundedJson.utf8(BoundedJson.read(input, maximum + 2));
            if (value.endsWith("\r\n")) {
                value = value.substring(0, value.length() - 2);
            } else if (value.endsWith("\n")) {
                value = value.substring(0, value.length() - 1);
            }
            int size = value.getBytes(StandardCharsets.UTF_8).length;
            if (size < minimum || size > maximum || placeholder(value)
                    || value.codePoints().anyMatch(code -> Character.isWhitespace(code) || Character.isISOControl(code)
                    || Character.getType(code) == Character.FORMAT)) {
                throw config(name);
            }
            return value;
        } catch (Exception exception) {
            throw config(name);
        }
    }

    private static InputStream openFile(String value, String name) {
        try {
            Path file = Path.of(required(value, name));
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw config(name);
            }
            return Files.newInputStream(file);
        } catch (Exception exception) {
            throw config(name);
        }
    }

    static IllegalStateException config(String name) {
        return new IllegalStateException("Invalid configuration: kb.notifier." + name);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || placeholder(value)) {
            throw config(name);
        }
        return value;
    }

    static String identity(String value, String name) {
        if (!required(value, name).matches("[a-z0-9][a-z0-9_-]{0,47}")) {
            throw config(name);
        }
        return value;
    }

    private static boolean placeholder(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("example") || lower.contains("changeme") || lower.contains("change-me")
                || lower.contains("replace-me") || lower.contains("replace_me") || lower.contains("your-")
                || lower.contains("your_") || lower.contains("${") || lower.contains("<")
                || lower.contains("placeholder") || lower.startsWith("replace") || lower.startsWith("dummy")
                || lower.contains("待填写") || lower.contains("替换")
                || lower.equals("secret") || lower.equals("token");
    }

    static URI root(String value, String name) {
        try {
            URI uri = URI.create(required(value, name));
            if (value.length() > 200 || !"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (!uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":")
                    || !value.equals(uri.toASCIIString()) || value.contains("\\")) {
                throw config(name);
            }
            return URI.create("https://" + uri.getRawAuthority());
        } catch (Exception exception) {
            throw config(name);
        }
    }

    private static int port(String value, int defaultValue, String name) {
        if (value == null) {
            return defaultValue;
        }
        try {
            int number = Integer.parseInt(value);
            if (number < 1024 || number > 65535) {
                throw config(name);
            }
            return number;
        } catch (Exception exception) {
            throw config(name);
        }
    }

    private static String address(String value, String name) {
        if (value == null) {
            return "127.0.0.1";
        }
        try {
            // 只接受字面内网地址，校验不触发 DNS 查询。通知器不继承业务生产配置。
            InetAddress parsed;
            if (value.contains(":") && value.matches("[0-9a-fA-F:]+")) {
                parsed = InetAddress.getByName(value);
            } else {
                String[] parts = value.split("\\.", -1);
                if (parts.length != 4) {
                    throw config(name);
                }
                byte[] ipv4 = new byte[4];
                for (int index = 0; index < parts.length; index++) {
                    if (!parts[index].matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(parts[index]) > 255) {
                        throw config(name);
                    }
                    ipv4[index] = (byte) Integer.parseInt(parts[index]);
                }
                parsed = InetAddress.getByAddress(ipv4);
            }
            byte[] bytes = parsed.getAddress();
            boolean uniqueLocal = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
            if (parsed.isAnyLocalAddress() || parsed.isMulticastAddress()
                    || !(parsed.isLoopbackAddress() || parsed.isSiteLocalAddress() || uniqueLocal)) {
                throw config(name);
            }
            return value;
        } catch (Exception exception) {
            throw config(name);
        }
    }

    @Override
    public String toString() {
        return "NotifierSettings[redacted]";
    }
}
