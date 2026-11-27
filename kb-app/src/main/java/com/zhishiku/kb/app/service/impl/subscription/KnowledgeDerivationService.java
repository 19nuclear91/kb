package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.config.KnowledgeSubscriptionProperties;
import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeWork;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 各阶段使用独立轮次预算，一阶段失败不跳过其他来源或阶段。 */
@Service
@RequiredArgsConstructor
public class KnowledgeDerivationService {
    private final KnowledgeDerivationTransaction transactions;
    private final KnowledgeSubscriptionProperties properties;
    private final KnowledgeMetrics metrics;
    private final String owner = "knowledge-" + UUID.randomUUID();

    public void runOnce() {
        try {
            transactions.discover();
        } catch (RuntimeException ignored) {
            metrics.result("discover", "failure");
        }
        run("match");
        try {
            for (int index = 0; index < properties.getCatchupWeeks(); index++) {
                if (!transactions.discoverWeek()) {
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            metrics.result("close", "failure");
        }
        run("weekly");
        run("delivery");
    }

    private void run(String stage) {
        List<KnowledgeWork.Claim> claims;
        try {
            claims = transactions.claim(stage, owner);
            if (claims == null || claims.size() > properties.getClaimLimit()) {
                throw new IllegalStateException("Invalid knowledge claims");
            }
        } catch (RuntimeException ignored) {
            metrics.result(stage, "failure");
            return;
        }
        for (var claim : claims) {
            if (claim.takeover()) {
                metrics.result(stage, "takeover");
            }
            try {
                boolean done = false;
                for (int batch = 0; batch < properties.getMaxBatchesPerEvent(); batch++) {
                    done = switch (stage) {
                        case "match" -> transactions.match(claim, owner);
                        case "weekly" -> transactions.week(claim, owner);
                        default -> transactions.deliver(claim, owner);
                    };
                    if (done) {
                        break;
                    }
                }
                if (!done) {
                    transactions.release(stage, claim, owner, 0, false);
                }
                metrics.result(stage, "success");
            } catch (KnowledgeLeaseLost ignored) {
                metrics.result(stage, "lease_lost");
            } catch (RuntimeException ignored) {
                metrics.result(stage, "failure");
                try {
                    transactions.release(stage, claim, owner, properties.backoff(claim.attempts()), true);
                } catch (RuntimeException unavailable) {
                    // 原租约到期后由其他执行接管，不记录正文、标识或底层异常。
                    metrics.result(stage, "lease_lost");
                }
            }
        }
    }
}
