package com.zhishiku.kb.app.service.answercard;

import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardIntent;
import com.zhishiku.kb.app.domain.vo.answercard.AnswerCardReceipt;

/** 答案卡独立命令入口，actor必须来自当前验证会话。 */
public interface AnswerCardWriteService {
    AnswerCardReceipt execute(long actor, String requestId, AnswerCardIntent intent);
    AnswerCardReceipt query(long actor, String requestId, AnswerCardIntent intent);
}
