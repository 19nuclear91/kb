package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.lifecycle.SessionCleanupJob;
import org.springframework.stereotype.Service;

/** 清理只减少存储残留；即时撤销依靠每次请求检查代次。 */
@Service
public class SessionCleanupService {
    private final SessionTokenIndex index;
    private final EpochSessionService sessions;

    public SessionCleanupService(SessionTokenIndex index, EpochSessionService sessions) {
        this.index = index;
        this.sessions = sessions;
    }

    public boolean cleanPage(SessionCleanupJob job) {
        var page = index.pending(job.userId(), job.revokedThroughEpoch(), 100);
        for (var token : page) {
            if (token.epoch() < 1 || token.epoch() > job.revokedThroughEpoch()) {
                throw new IllegalStateException("令牌清理索引越界");
            }
            var recorded = sessions.recorded(token.value());
            if (recorded == null || (recorded.userId() == job.userId() && recorded.authEpoch() <= job.revokedThroughEpoch())) {
                sessions.revoke(token.value());
            }
            index.remove(job.userId(), token);
        }
        return page.size() < 100;
    }
}
