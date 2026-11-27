package com.zhishiku.kb.app.domain.subscription;

import java.time.Instant;

/** kb_knowledge_command_receipt 表：本人请求绑定及仅含最小结果的回执。 */
public record SubscriptionCommand(
        /** 服务端确认的操作用户ID。 */
        long actorId,
        /** 本人请求标识，1至128个非控制字符。 */
        String requestId,
        /** SUBSCRIBE订阅、CANCEL取消、PREFERENCE修改频率。 */
        String operation,
        /** 完整规范输入SHA256绑定，包含目标代次和预期版本。 */
        String inputDigest,
        /** JSON最小回执，事务未完成时为空，不含资源名称或正文。 */
        String receipt,
        /** 初次登记时间，UTC时间点。 */
        Instant createdAt
) {
}
