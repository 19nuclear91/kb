package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.model.ParseTask;

/**
 * Worker 失败告警的最小适配接口；可替换为 webhook、消息队列或监控告警。
 */
public interface TaskFailureAlert {

    void onFailure(ParseTask task, String reason, boolean exhausted);
}
