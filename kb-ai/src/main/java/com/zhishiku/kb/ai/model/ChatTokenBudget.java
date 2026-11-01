package com.zhishiku.kb.ai.model;

/**
 * 由部署方提供经核验的模型预算；本工程不注册生产估算器。
 * 方法必须纯本地、确定性、资源有界，不得调用网络或修改消息。
 * 上界须包含消息角色、system、历史、材料、原问题和供应商消息包装开销，不能用字符数冒充证明。
 */
public interface ChatTokenBudget {
    /** 返回实际核验过的部署、模型与消息格式绑定。 */
    ChatModelIdentity identity();

    /** 返回经核验的模型上下文窗口。 */
    long contextWindowTokens();

    /** 计算发送视图的输入 token 上界；不得将估算结果当作实际 usage。 */
    long inputTokenUpperBound(PreparedChatRequest request);
}
