package com.zhishiku.kb.app.document.model;

import lombok.Data;

/** 已锁住的现有普通解析任务，不新造第二条版本任务。 */
@Data
public class CandidateRefreshTask {
    private long id;
    private long versionId;
    private long executionGeneration;
    private long claimToken;
}
