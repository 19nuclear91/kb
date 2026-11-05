package com.zhishiku.kb.common.office;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 固定版本的只读协议边界。只描述允许的路径与操作，不持有授权结果。
 */
public final class OfficeProtocol {
    public static final String ADAPTER_VERSION = "onlyoffice-10.0.0-v1";
    public static final String IMAGE = "onlyoffice/documentserver:10.0.0@sha256:ca17889f10b11141cc6441836aebdd2835cf3e1b49681b2fd6e155c94b7850b3";
    public static final String COOKIE_NAME = "__Secure-kb-office-preview";
    public static final int PROTOCOL_VERSION = 1;
    public static final Set<String> FORMATS = Set.of("docx", "xlsx", "pptx");
    public static final Set<String> CLIENT_MESSAGES = Set.of("auth", "openDocument", "close", "extendSession", "authChangesAck");
    public static final Set<String> OPEN_COMMANDS = Set.of("open", "pathurl", "pathurls");
    private static final Pattern SID = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern KEY = Pattern.compile("kb-[a-f0-9]{64}");
    private static final Pattern SAFE_PATH = Pattern.compile("/[A-Za-z0-9_./@+~-]+");
    private static final Pattern BUILD_PREFIX = Pattern.compile("^/10\\.0\\.0-[A-Za-z0-9._-]+(?=/)");
    private static final Set<String> STATIC_ROOTS = Set.of("/web-apps/", "/sdkjs/", "/fonts/");

    private OfficeProtocol() {
    }

    public enum Resource {
        STATIC, CACHE, SOCKET, DENIED
    }

    public static boolean validSessionId(String value) {
        return value != null && SID.matcher(value).matches();
    }

    public static boolean validKey(String value) {
        return value != null && KEY.matcher(value).matches();
    }

    public static String extension(String fileName) {
        if (fileName == null || fileName.lastIndexOf('.') < 0) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    public static String documentType(String extension) {
        return switch (extension) {
            case "docx" -> "word";
            case "xlsx" -> "cell";
            case "pptx" -> "slide";
            default -> throw new IllegalArgumentException("不支持的 Office 格式");
        };
    }

    /** 路径必须是未经解码的上游路径；含转义或非规范片段时拒绝，避免双重解码。 */
    public static Resource resource(String method, String rawPath, String key) {
        if (rawPath == null || rawPath.length() > 2048 || !validKey(key)
                || !SAFE_PATH.matcher(rawPath).matches() || rawPath.contains("//")) {
            return Resource.DENIED;
        }
        for (String segment : rawPath.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return Resource.DENIED;
            }
        }
        String path = BUILD_PREFIX.matcher(rawPath).replaceFirst("");
        if (path.equals("/doc/" + key + "/c") || path.equals("/doc/" + key + "/c/")) {
            return "GET".equals(method) || "POST".equals(method) ? Resource.SOCKET : Resource.DENIED;
        }
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return Resource.DENIED;
        }
        String cacheRoot = "/cache/files/data/" + key + "/";
        if (path.startsWith(cacheRoot)) {
            String relative = path.substring(cacheRoot.length());
            // 原件、导出文件及其他会话的临时缓存永远不属于阅读资源。
            if (relative.equals("Editor.bin/Editor.bin") || relative.equals("Editor.json/Editor.json")
                    || relative.matches("media/[A-Za-z0-9_-]+\\.(png|jpg|jpeg|gif|svg|webp|emf|wmf)/[A-Za-z0-9_.-]+")) {
                return Resource.CACHE;
            }
            return Resource.DENIED;
        }
        if (path.equals("/web-apps/apps/api/documents/api.js") || path.equals("/themes.json")) {
            return Resource.STATIC;
        }
        if (STATIC_ROOTS.stream().anyMatch(path::startsWith)
                && !path.endsWith(".map") && !path.contains("service_worker") && !path.contains("/plugins/")) {
            return Resource.STATIC;
        }
        return Resource.DENIED;
    }
}
