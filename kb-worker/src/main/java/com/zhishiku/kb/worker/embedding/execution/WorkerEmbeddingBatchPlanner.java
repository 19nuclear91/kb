package com.zhishiku.kb.worker.embedding.execution;

import com.zhishiku.kb.common.embedding.CallBudget;
import com.zhishiku.kb.common.embedding.EmbeddingBatchPlanner;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class WorkerEmbeddingBatchPlanner implements EmbeddingBatchPlanner {
    private final WorkerEmbeddingJson json;
    private final int maximum;

    public WorkerEmbeddingBatchPlanner(WorkerEmbeddingSettings settings, WorkerEmbeddingJson json) {
        this.json = json;
        maximum = settings.maxRequestBytes();
    }

    @Override
    public List<Integer> plan(EmbeddingProfileDefinition definition, List<String> inputs, CallBudget budget) {
        if (budget == null) {
            throw new IllegalStateException("Worker embedding budget required");
        }
        budget.check();
        if (inputs.isEmpty()) {
            return List.of();
        }
        int envelope = json.count(Map.of("model", definition.profile().model(), "input", List.of(),
                "dimensions", definition.profile().dimension()), budget);
        int[] sizes = new int[inputs.size()];
        for (int index = 0; index < inputs.size(); index++) {
            int size = json.count(inputs.get(index), budget);
            if (size > maximum - envelope) {
                throw new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.REQUEST_LIMIT);
            }
            sizes[index] = size;
        }
        var ends = new ArrayList<Integer>();
        int start = 0;
        int bytes = envelope;
        for (int index = 0; index < sizes.length; index++) {
            budget.check();
            int comma = index == start ? 0 : 1;
            if (index - start == definition.connection().batchSize() || sizes[index] + comma > maximum - bytes) {
                ends.add(index);
                start = index;
                bytes = envelope;
                comma = 0;
            }
            bytes += sizes[index] + comma;
        }
        ends.add(inputs.size());
        return List.copyOf(ends);
    }
}
