package com.zhishiku.kb.app.service.answercard;

import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardPageInput;
import com.zhishiku.kb.app.domain.vo.answercard.AnswerCardViews;

public interface AnswerCardReadService {
    AnswerCardViews.Page<AnswerCardViews.Item> browse(long actor, AnswerCardPageInput input);
    AnswerCardViews.Search search(long actor, String query, Long spaceId, int limit);
    AnswerCardViews.Detail detail(long actor, long cardId, long revisionId);
    AnswerCardViews.Page<AnswerCardViews.Workspace> workspaces(long actor, AnswerCardPageInput input);
    AnswerCardViews.Workspace workspace(long actor, long cardId);
    AnswerCardViews.Page<AnswerCardViews.History> history(long actor, long cardId, AnswerCardPageInput input);
    AnswerCardViews.Page<AnswerCardViews.Management> management(long actor, AnswerCardPageInput input);
    AnswerCardViews.Management management(long actor, long cardId);
    AnswerCardViews.Page<AnswerCardViews.Person> candidates(long actor, long cardId, AnswerCardPageInput input);
    AnswerCardViews.Page<AnswerCardViews.SourceOption> sources(long actor, AnswerCardPageInput input);
}
