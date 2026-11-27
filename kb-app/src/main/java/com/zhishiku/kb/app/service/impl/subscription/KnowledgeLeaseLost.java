package com.zhishiku.kb.app.service.impl.subscription;

/** 旧领取者已失去工作资格，不得推进新进度或标记新的失败。 */
public final class KnowledgeLeaseLost extends RuntimeException {
    public KnowledgeLeaseLost() {
        super("KNOWLEDGE_LEASE_LOST");
    }
}
