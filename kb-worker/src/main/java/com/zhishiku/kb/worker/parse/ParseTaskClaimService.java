package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.parse.mapper.ParseTaskMapper;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ParseTaskClaimService {
    private final EmbeddingStateMapper state;
    private final EmbeddingClientRegistry clients;
    private final ParseTaskMapper tasks;
    private final DocumentParser parser;

    public ParseTaskClaimService(EmbeddingStateMapper state, EmbeddingClientRegistry clients,
                                 ParseTaskMapper tasks, DocumentParser parser) {
        this.state = state;
        this.clients = clients;
        this.tasks = tasks;
        this.parser = parser;
    }

    @Transactional
    public List<ParseTask> claim(String owner, int leaseSeconds, int limit) {
        if (owner == null || owner.isBlank() || leaseSeconds <= 0 || limit <= 0) {
            throw new IllegalArgumentException("invalid parse claim parameters");
        }
        // 未配置第三方服务时保留队列，不领取租约、不增加尝试次数或修改版本状态。
        if (!parser.isAvailable()) return List.of();
        var control = state.lockShared();
        if (control == null) throw new IllegalStateException("embedding control unavailable");
        if (!control.active()) return List.of();
        var client = clients.find(control.activeProfileId(), control.activeFingerprint());
        if (client.isEmpty() || client.get().role() != EmbeddingRole.DOCUMENT) return List.of();

        var claimed = tasks.claim(owner, leaseSeconds, limit, control.activeProfileId(), control.epoch());
        // 版本状态也在领取事务内更新，避免维护切换后迟到的 runner 覆盖等待状态。
        for (var task : claimed) tasks.updateVersionIndexStatus(task.versionId(), "parsing", null);
        return claimed;
    }
}
