package com.zhishiku.kb.common.security;

/** 独立标准答案路径及请求上限，不授予会话或资源资格。 */
public final class AnswerCardEndpoints {
    public static final String PREFIX = "/api/answer-cards";

    private AnswerCardEndpoints() {
    }

    public static boolean matches(String path) {
        return path != null && (path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
    }

    public static int bodyLimit(String path) {
        return (PREFIX + "/search").equals(path) ? 16384 : 131072;
    }
}
