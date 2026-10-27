package com.zhishiku.kb.app.auth.sso;

/**
 * 仅由服务端成员核验构造，不作为 HTTP 输入输出模型。
 *
 * @param platform      固定平台
 * @param enterpriseId  已核验企业
 * @param applicationId 已核验应用作用域
 * @param subjectKind   标识类型
 * @param subject       区分大小写的企业成员标识
 */
public record VerifiedExternalIdentity(String platform, String enterpriseId, String applicationId,
                                       String subjectKind, String subject) {
    public VerifiedExternalIdentity {
        if (!SsoSettings.PLATFORMS.contains(platform)) throw SsoException.unavailable();
        for (String value : new String[]{enterpriseId, applicationId, subjectKind, subject}) {
            if (value == null || value.isBlank() || value.length() > 512
                    || value.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)))
                throw SsoException.unavailable();
        }
    }

    public String provider() {
        return SsoScope.provider(platform, enterpriseId, applicationId, subjectKind);
    }
}
