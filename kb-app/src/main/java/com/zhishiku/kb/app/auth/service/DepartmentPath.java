package com.zhishiku.kb.app.auth.service;

/**
 * 物化部门树的纯路径运算。
 */
final class DepartmentPath {

    private DepartmentPath() {
    }

    static String forRoot(long id) {
        return "/" + id + "/";
    }

    static String forChild(String parentPath, long id) {
        String prefix = parentPath == null || parentPath.isBlank() ? "/" : parentPath;
        if (!prefix.startsWith("/")) prefix = "/" + prefix;
        if (!prefix.endsWith("/")) prefix += "/";
        return prefix + id + "/";
    }

    static boolean isSameOrDescendant(String candidatePath, String ancestorPath) {
        if (candidatePath == null || ancestorPath == null) return false;
        String normalized = ancestorPath.endsWith("/") ? ancestorPath : ancestorPath + "/";
        return candidatePath.equals(normalized) || candidatePath.startsWith(normalized);
    }

    static String requirePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("部门路径不能为空");
        }
        return path.endsWith("/") ? path : path + "/";
    }
}
