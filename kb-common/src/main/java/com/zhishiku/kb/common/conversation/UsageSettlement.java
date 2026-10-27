package com.zhishiku.kb.common.conversation;

/**
 * 真实累计用量与保守计费分离。
 */
public final class UsageSettlement {
    private UsageSettlement() {
    }

    /**
     * 结算值。
     *
     * @param status           真实用量状态
     * @param promptTokens     累计输入量或空
     * @param completionTokens 累计输出量或空
     * @param chargedTokens    扣除额度
     */
    public record Value(String status, Long promptTokens, Long completionTokens, long chargedTokens) {
    }

    public static Value calculate(boolean dispatched, boolean finalUsage, Long prompt, Long completion, long reserved) {
        if (!dispatched) return new Value("NOT_INVOKED", 0L, 0L, 0L);
        if (!finalUsage) return new Value("UNKNOWN", null, null, reserved);
        if (prompt == null || completion == null || prompt < 0 || completion < 0)
            throw new IllegalArgumentException("累计用量无效");
        return new Value("KNOWN", prompt, completion, Math.addExact(prompt, completion));
    }

    public static String digest(String turn, String finalizedAt, String lease, String event, long user,
                                long department, long reserved,
                                String generation, Value usage) {
        return RequestSignature.digest(turn + "|" + finalizedAt + "|" + lease + "|" + event + "|" + user + "|" + department + "|" + reserved + "|" +
                generation + "|" + usage.status() + "|" + usage.promptTokens() + "|" + usage.completionTokens() + "|" + usage.chargedTokens());
    }
}

