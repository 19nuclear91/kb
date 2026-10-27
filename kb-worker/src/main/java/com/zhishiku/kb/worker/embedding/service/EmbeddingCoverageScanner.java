package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.common.embedding.EmbeddingControl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbeddingCoverageScanner {
    private final EmbeddingVerificationService verification;

    public EmbeddingCoverageScanner(EmbeddingVerificationService verification) {
        this.verification = verification;
    }

    @Transactional(propagation = Propagation.NEVER)
    public String scan(EmbeddingControl control) {
        long after = 0;
        while (true) {
            var page = verification.page(control, after);
            if (page.isEmpty()) break;
            for (var chunk : page) {
                if (chunk.id() <= after || !chunk.valid()) return "COVERAGE_INVALID";
                after = chunk.id();
            }
        }
        return verification.check(control);
    }
}
