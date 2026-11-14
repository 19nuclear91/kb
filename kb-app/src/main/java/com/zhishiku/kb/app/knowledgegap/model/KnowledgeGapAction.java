package com.zhishiku.kb.app.knowledgegap.model;

/** 明确动作集合，不提供重开、自动确认、删除或编辑历史动作。 */
public enum KnowledgeGapAction {
    CREATE,
    ASSIGN,
    NOTE,
    RETURN,
    SOLUTION,
    CONFIRM,
    REJECT_SOLUTION,
    WITHDRAW,
    CLOSE
}
