package com.zhishiku.kb.ai.chat;

/**
 * 上游明确拒绝了已认证的用户或部门。
 */
public final class QuotaPrincipalRejectedException extends QuotaUnavailableException {
    public QuotaPrincipalRejectedException(String message) {
        super(message);
    }
}
