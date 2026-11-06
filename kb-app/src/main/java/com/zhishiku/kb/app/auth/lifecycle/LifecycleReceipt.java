package com.zhishiku.kb.app.auth.lifecycle;

public record LifecycleReceipt(long actorUserId, String requestId, String operation, String targetKey,
                                String bindingDigest, String response) {
    @Override
    public String toString() {
        return "LifecycleReceipt[redacted]";
    }
}
