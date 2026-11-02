package com.zhishiku.kb.ai.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 固定预算不足类别，不携带问题、材料或原始异常。 */
public final class InputBudgetExceededException extends ResponseStatusException {
    public static final String CODE = "INPUT_BUDGET_EXCEEDED";
    public static final String MESSAGE = "输入超出模型预算";

    public InputBudgetExceededException() {
        super(HttpStatus.BAD_REQUEST, MESSAGE);
    }
}
