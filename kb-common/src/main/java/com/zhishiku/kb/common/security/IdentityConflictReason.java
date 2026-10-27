package com.zhishiku.kb.common.security;

/**
 * 通用 CONFLICT 之下由实际业务检查给出的细分原因。
 *
 * <p>只能在对应检查点显式附加，不得从异常文案、SQL 或外部错误推断。
 */
public enum IdentityConflictReason {
    DEPARTMENT_NOT_EMPTY,
    ROLE_IN_USE,
    LAST_MANAGER_REQUIRED,
    IDENTIFIER_CONFLICT,
    SPACE_NOT_EMPTY,
    SOURCE_MISMATCH,
    TARGET_ARCHIVED,
    ALREADY_IN_TARGET
}
