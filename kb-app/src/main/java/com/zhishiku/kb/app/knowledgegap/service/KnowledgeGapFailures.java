package com.zhishiku.kb.app.knowledgegap.service;

import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;

import java.util.function.Supplier;

/** 只投影固定错误，不传播仓储异常中的共享正文或 SQL 参数。 */
final class KnowledgeGapFailures {
    private KnowledgeGapFailures() {
    }

    static <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (ApiException | QueryException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
    }

    static ApiException unavailable() {
        return ApiException.unavailable("知识问题单服务暂不可用");
    }
}
