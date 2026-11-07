package com.zhishiku.kb.common.embedding;

import java.util.ArrayList;
import java.util.List;

/** 对已完成 profile 转换的输入规划连续子批，返回各批排他的结束位置。 */
@FunctionalInterface
public interface EmbeddingBatchPlanner {
    List<Integer> plan(EmbeddingProfileDefinition definition, List<String> inputs, CallBudget budget);

    static EmbeddingBatchPlanner byCount() {
        return (definition, inputs, budget) -> {
            var ends = new ArrayList<Integer>();
            for (int start = 0; start < inputs.size(); ) {
                int end = start + Math.min(definition.connection().batchSize(), inputs.size() - start);
                ends.add(end);
                start = end;
            }
            return List.copyOf(ends);
        };
    }
}
