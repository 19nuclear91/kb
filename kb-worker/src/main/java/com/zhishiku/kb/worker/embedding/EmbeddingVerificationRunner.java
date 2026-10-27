package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.worker.embedding.model.RebuildLeaseLostException;
import com.zhishiku.kb.worker.embedding.service.EmbeddingActivationService;
import com.zhishiku.kb.worker.embedding.service.EmbeddingCoverageScanner;
import com.zhishiku.kb.worker.embedding.service.EmbeddingVerificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingVerificationRunner {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingVerificationRunner.class);
    private final EmbeddingVerificationService verification;
    private final EmbeddingCoverageScanner scanner;
    private final EmbeddingActivationService activation;

    public EmbeddingVerificationRunner(EmbeddingVerificationService verification, EmbeddingCoverageScanner scanner,
                                       EmbeddingActivationService activation) {
        this.verification = verification;
        this.scanner = scanner;
        this.activation = activation;
    }

    @Scheduled(fixedDelay = 5000)
    public void tick() {
        try {
            var control = verification.begin();
            if (control == null) return;
            try {
                String error = scanner.scan(control);
                if (error == null) activation.activate(control);
                else activation.block(control, error);
            } catch (RebuildLeaseLostException stale) {
                // 其他实例已推进状态，不能覆盖其结果。
            } catch (DataAccessException unavailable) {
                activation.block(control, "DATABASE_ERROR");
            }
        } catch (RuntimeException unavailable) {
            log.warn("embedding verification unavailable");
        }
    }
}
