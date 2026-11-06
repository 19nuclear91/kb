package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.SessionCleanupMapper;
import com.zhishiku.kb.app.auth.service.SessionCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/** 数据库只保存代次和租约，令牌留在 Redis；关闭钉钉订阅不停止清理。 */
@Component
public class SessionCleanupWorker {
    private static final Logger LOG = LoggerFactory.getLogger(SessionCleanupWorker.class);
    private final SessionCleanupMapper jobs;
    private final SessionCleanupService cleanup;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final LifecycleObservations observations;

    @Autowired
    public SessionCleanupWorker(SessionCleanupMapper jobs, SessionCleanupService cleanup,
                                 PlatformTransactionManager manager, LifecycleObservations observations) {
        this(jobs, cleanup, new TransactionTemplate(manager), Clock.systemUTC(), observations);
        this.transaction.setTimeout(5);
    }

    public SessionCleanupWorker(SessionCleanupMapper jobs, SessionCleanupService cleanup,
                                 TransactionTemplate transaction, Clock clock, LifecycleObservations observations) {
        this.jobs = jobs;
        this.cleanup = cleanup;
        this.transaction = transaction;
        this.clock = clock;
        this.observations = observations;
    }

    @Scheduled(fixedDelay = 5000)
    public void tick() {
        try {
            for (int count = 0; count < 4; count++) {
                if (!runOne()) {
                    return;
                }
            }
        } catch (RuntimeException failed) {
            observations.cleanup(LifecycleObservations.Cleanup.FAILED);
            LOG.warn("会话清理暂不可用 category=dependency");
        }
    }

    public boolean runOne() {
        UUID owner = UUID.randomUUID();
        SessionCleanupJob job = transaction.execute(status -> {
            var found = jobs.next(clock.instant());
            if (found != null && jobs.claim(found.userId(), found.revokedThroughEpoch(), owner,
                    clock.instant().plusSeconds(30)) != 1) {
                throw new IllegalStateException("清理任务领取失败");
            }
            return found;
        });
        if (job == null) {
            return false;
        }
        boolean done;
        boolean failed = false;
        try {
            done = cleanup.cleanPage(job);
        } catch (RuntimeException unavailable) {
            done = false;
            failed = true;
        }
        boolean completed = done;
        boolean retry = failed;
        long delay = failed ? Math.min(300, 5L << Math.min(job.attempts(), 6)) : 0;
        Integer released = transaction.execute(status -> jobs.release(job.userId(), job.revokedThroughEpoch(), owner,
                completed, retry, clock.instant(), clock.instant().plusSeconds(delay)));
        observations.cleanup(failed || released == null || released != 1 ? LifecycleObservations.Cleanup.FAILED
                : completed ? LifecycleObservations.Cleanup.DONE : LifecycleObservations.Cleanup.PAGE);
        return true;
    }
}
