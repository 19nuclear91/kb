package com.zhishiku.kb.app.document.model;

import lombok.Data;

/** 在文档／版本锁内读取的实际分块完整性，不从任务成功状态推断。 */
@Data
public class IndexIntegrity {
    private long chunkCount;
    private long validCount;
    private long distinctIndexes;
    private Integer firstIndex;
    private Integer lastIndex;

    public boolean complete(Integer expected) {
        return expected != null && expected > 0 && chunkCount == expected && validCount == expected
                && distinctIndexes == expected && Integer.valueOf(0).equals(firstIndex)
                && Integer.valueOf(expected - 1).equals(lastIndex);
    }
}
