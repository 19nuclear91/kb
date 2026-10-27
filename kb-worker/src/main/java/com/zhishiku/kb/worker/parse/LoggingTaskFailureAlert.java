package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.model.ParseTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认告警实现：结构化错误日志由日志/监控系统采集。
 */
@Component
public class LoggingTaskFailureAlert implements TaskFailureAlert {

    private static final Logger log = LoggerFactory.getLogger(LoggingTaskFailureAlert.class);

    @Override
    public void onFailure(ParseTask task, String reason, boolean exhausted) {
        log.error("解析任务失败告警 taskId={} versionId={} attempts={}/{} exhausted={} reason={}",
                task.id(), task.versionId(), task.attempts(), task.maxAttempts(), exhausted, reason);
    }
}
