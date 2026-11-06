package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.common.api.ApiException;

public final class LifecycleFailures {
    private LifecycleFailures() {
    }

    public static ApiException unavailable() {
        return ApiException.unavailable("生命周期服务暂不可用，请稍后重新核对");
    }
}
