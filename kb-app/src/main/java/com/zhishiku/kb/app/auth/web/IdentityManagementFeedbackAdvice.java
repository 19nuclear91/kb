package com.zhishiku.kb.app.auth.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.space.web.SpaceController;
import com.zhishiku.kb.app.space.web.DocumentAclController;
import com.zhishiku.kb.app.space.web.DocumentRelocationController;
import com.zhishiku.kb.app.document.web.DocumentSecurityLevelController;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Set;

/**
 * 为身份、空间与文档管理的指定写入口增加 reasonCode 投影。
 *
 * <p>读取、外部身份写入、旧 {@code /api/identity} 别名及其他异常全部交回既有处理；
 * 投影只输出固定分类与固定文案，不回显异常正文或请求值。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {IdentityManagementController.class, RoleManagementController.class,
        SpaceController.class, DocumentAclController.class, DocumentRelocationController.class,
        DocumentSecurityLevelController.class})
public class IdentityManagementFeedbackAdvice {
    private static final Logger log = LoggerFactory.getLogger(IdentityManagementFeedbackAdvice.class);
    private static final String PREFIX = "/api/admin/identity";
    static final Set<String> WRITES = Set.of(
            "POST " + PREFIX + "/users",
            "PATCH " + PREFIX + "/users/{userId}/department",
            "PATCH " + PREFIX + "/users/{userId}/enabled",
            "PATCH " + PREFIX + "/users/{userId}/password",
            "PATCH " + PREFIX + "/users/{userId}/clearance",
            "POST " + PREFIX + "/users/{userId}/roles",
            "DELETE " + PREFIX + "/users/{userId}/roles/{roleId}",
            "POST " + PREFIX + "/departments",
            "PATCH " + PREFIX + "/departments/{departmentId}/name",
            "PATCH " + PREFIX + "/departments/{departmentId}/parent",
            "PATCH " + PREFIX + "/departments/{departmentId}/enabled",
            "POST " + PREFIX + "/roles",
            "PATCH " + PREFIX + "/roles/{roleId}",
            "DELETE " + PREFIX + "/roles/{roleId}",
            "PUT " + PREFIX + "/roles/{roleId}/permissions",
            "POST /api/admin/spaces",
            "PATCH /api/admin/spaces/{spaceId}",
            "POST /api/admin/spaces/{spaceId}/archive",
            "POST /api/admin/spaces/{spaceId}/unarchive",
            "DELETE /api/admin/spaces/{spaceId}",
            "POST /api/admin/spaces/{spaceId}/acl",
            "DELETE /api/admin/spaces/{spaceId}/acl",
            "POST /api/admin/documents/{documentId}/acl",
            "DELETE /api/admin/documents/{documentId}/acl",
            "PATCH /api/admin/documents/{documentId}/space",
            "PATCH /api/admin/documents/{documentId}/security-level");

    private final GlobalExceptionHandler fallback;

    public IdentityManagementFeedbackAdvice(GlobalExceptionHandler fallback) {
        this.fallback = fallback;
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<?> handle(IdentityException error, HttpServletRequest request) {
        String reason = whitelisted(request) ? reason(error) : null;
        if (reason == null) return fallback.handleIdentity(error);
        log.debug("身份管理写入被拒绝 reason={} status={}", reason, error.httpStatus());
        return ResponseEntity.status(error.httpStatus()).body(Failure.of(reason));
    }

    private static boolean whitelisted(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null && WRITES.contains(request.getMethod() + " " + pattern);
    }

    /**
     * 只认可错误码与原 HTTP 状态一致的已知分类；不一致时视为未识别，交回原响应。
     */
    private static String reason(IdentityException error) {
        IdentityErrorCode code = error.errorCode();
        if (code == null) return null;
        int expected = switch (code) {
            case NOT_FOUND -> 404;
            case FORBIDDEN, DISABLED, PASSWORD_CHANGE_REQUIRED -> 403;
            case CONFLICT, INVALID_HIERARCHY, INVALID_PARENT, INVALID_VERSION -> 409;
            case INVALID_REQUEST -> 400;
            case UNAUTHORIZED -> 401;
        };
        if (error.httpStatus() != expected) return null;
        return error.reason().filter(ignored -> code == IdentityErrorCode.CONFLICT)
                .map(IdentityConflictReason::name).orElse(code.name());
    }

    static String text(String reason) {
        return switch (reason) {
            case "INVALID_VERSION" -> "数据已被其他操作更新，请重新读取后确认。";
            case "DEPARTMENT_NOT_EMPTY" -> "部门仍有启用的子部门或用户，请先处理。";
            case "ROLE_IN_USE" -> "角色仍有引用，请先处理绑定或授权。";
            case "LAST_MANAGER_REQUIRED" -> "该操作会使系统失去最后一名合格管理员。";
            case "IDENTIFIER_CONFLICT" -> "用户名或编码已存在。";
            case "SPACE_NOT_EMPTY" -> "空间仍有文档，请改用归档或先处理文档。";
            case "SOURCE_MISMATCH" -> "文档归属已变化，请重新读取后确认。";
            case "TARGET_ARCHIVED" -> "目标空间已归档，请重新选择。";
            case "ALREADY_IN_TARGET" -> "文档已在目标空间，请重新读取后确认。";
            case "INVALID_HIERARCHY", "INVALID_PARENT" -> "部门层级不符合要求，请重新读取后确认。";
            case "CONFLICT" -> "当前状态已变化，请重新读取后确认。";
            case "INVALID_REQUEST" -> "请求内容不符合要求。";
            case "FORBIDDEN" -> "当前操作被拒绝。";
            case "DISABLED" -> "目标或关联对象已停用。";
            case "PASSWORD_CHANGE_REQUIRED" -> "请先修改密码。";
            case "NOT_FOUND" -> "目标不存在或当前不可访问。";
            case "UNAUTHORIZED" -> "登录状态无效或已过期。";
            default -> throw new IllegalArgumentException("未知身份管理错误分类");
        };
    }

    /**
     * 与 {@link R} 失败字段兼容的增量响应；data 恒为 null 且必须显式输出。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Failure(int code, String message, Object data, String reasonCode) {
        static Failure of(String reason) {
            return new Failure(R.FAILURE, text(reason), null, reason);
        }
    }
}
