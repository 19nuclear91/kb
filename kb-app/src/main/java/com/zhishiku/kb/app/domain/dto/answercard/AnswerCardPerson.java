package com.zhishiku.kb.app.domain.dto.answercard;

/** 仅供受控责任人选择的最小身份投影。 */
public record AnswerCardPerson(
        long userId,
        String displayName
) {
}
