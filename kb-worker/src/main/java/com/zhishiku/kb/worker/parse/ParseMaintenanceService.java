package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.parse.mapper.ParseMaintenanceMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParseMaintenanceService {
    private final EmbeddingStateMapper state;
    private final ParseMaintenanceMapper tasks;

    public ParseMaintenanceService(EmbeddingStateMapper state, ParseMaintenanceMapper tasks) {
        this.state = state;
        this.tasks = tasks;
    }

    @Scheduled(fixedDelayString = "${kb.embedding.quiesce-interval-ms:2000}")
    @Transactional(timeout = 10)
    public void quiesce() {
        var control = state.lockExclusive();
        if (control == null) throw new IllegalStateException("embedding control unavailable");
        if (!"QUIESCING".equals(control.state())) return;
        var run = state.run(control.runId());
        if (run == null || !"QUIESCING".equals(run.state())) {
            throw new IllegalStateException("embedding run state mismatch");
        }
        for (var claim : tasks.lockRunning()) {
            if (claim.epoch() != null && claim.epoch() >= control.epoch()) {
                throw new IllegalStateException("unexpected parse claim epoch during quiescing");
            }
            // 只有带身份的本次领取可退还一次预算；旧协议未知领取不能猜测其失败历史。
            int refund = claim.epoch() != null && claim.claimToken() > 0 && claim.attempts() > 0 ? 1 : 0;
            if (tasks.defer(claim.id(), claim.claimToken(), control.epoch(), refund) != 1) {
                throw new IllegalStateException("parse maintenance claim conflict");
            }
            tasks.event(claim, "maintenance:" + claim.id() + ":" + claim.claimToken());
            tasks.pendingVersion(claim.versionId());
        }
        if (tasks.runningCount() != 0) return;
        if (tasks.advanceRun(run.runId(), run.version()) != 1
                || tasks.advanceControl(run.runId(), control.epoch()) != 1) {
            throw new IllegalStateException("embedding quiesce state conflict");
        }
    }
}
