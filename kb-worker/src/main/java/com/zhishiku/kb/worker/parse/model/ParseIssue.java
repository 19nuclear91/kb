package com.zhishiku.kb.worker.parse.model;

/**
 * 解析过程中的可追踪问题；警告不阻断索引，错误由流水线决定是否拒绝发布。
 *
 * @param severity 问题级别
 * @param code     稳定问题代码
 * @param message  脱敏后的问题说明
 * @param location 问题来源坐标
 */
public record ParseIssue(Severity severity, String code, String message,
                         SourceLocation location) {

    public enum Severity {WARNING, ERROR}

    public ParseIssue {
        if (severity == null) severity = Severity.ERROR;
        if (code == null || code.isBlank()) code = "UNKNOWN";
        if (message == null || message.isBlank()) message = "解析失败";
        if (location == null) location = SourceLocation.unknown();
        code = code.length() <= 128 ? code : code.substring(0, 128);
        message = message.length() <= 4096 ? message : message.substring(0, 4096);
    }
}
