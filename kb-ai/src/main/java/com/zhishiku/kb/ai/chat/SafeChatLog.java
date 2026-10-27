package com.zhishiku.kb.ai.chat;

import org.slf4j.Logger;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.TimeoutException;

/**
 * 异常可能包含问题、令牌或上游正文；日志只写固定类别和数值状态。
 */
final class SafeChatLog {
    private SafeChatLog() {
    }

    static void warn(Logger log, String stage, Throwable error) {
        log.warn("AI 操作失败 stage={} category={} status={}", stage, category(error), status(error));
    }

    static void error(Logger log, String stage, Throwable error) {
        log.error("AI 操作失败 stage={} category={} status={}", stage, category(error), status(error));
    }

    private static String category(Throwable error) {
        if (error instanceof WebClientResponseException) return "upstream_response";
        if (error instanceof WebClientRequestException) return "transport";
        if (error instanceof TimeoutException) return "timeout";
        return "internal";
    }

    private static int status(Throwable error) {
        return error instanceof WebClientResponseException response
                ? response.getStatusCode().value() : 0;
    }
}
