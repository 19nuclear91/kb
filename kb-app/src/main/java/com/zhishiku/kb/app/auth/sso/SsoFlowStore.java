package com.zhishiku.kb.app.auth.sso;

/**
 * 所有阶段写入均需原子校验；存储故障不得退回进程内状态。
 */
public interface SsoFlowStore {
    void create(SsoFlow flow, int startsPerMinute, int maxActive);

    SsoFlow claim(String connection, String browserDigest, String stateDigest, long now);

    void ready(SsoFlow flow, long now);

    SsoFlow current(String browserDigest, long now);

    SsoFlow consume(SsoFlow flow, String resultDigest, long now);
}
