package com.zhishiku.kb.app.document.imports.service;

import com.zhishiku.kb.app.document.imports.ImportProperties;
import com.zhishiku.kb.app.document.imports.mapper.ImportMapper;
import com.zhishiku.kb.app.document.imports.model.ImportModels.AttemptState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.FailureCode;
import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 恢复只撤销资格，不访问原件、不依赖在线身份或解析器。 */
@Service
public class ImportRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(ImportRecoveryService.class);
    private final ImportMapper mapper;
    private final ImportProperties properties;
    private final TransactionTemplate transaction;

    public ImportRecoveryService(ImportMapper mapper, ImportProperties properties, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.properties = properties;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    public int recoverCycle() {
        int recovered = 0;
        try {
            for (long itemId : mapper.expired(properties.recoveryLimit())) {
                try {
                    if (recover(itemId)) {
                        recovered++;
                    }
                } catch (RuntimeException failed) {
                    log.warn("IMPORT_RECOVERY_ITEM_UNAVAILABLE");
                }
            }
        } catch (RuntimeException failed) {
            log.warn("IMPORT_RECOVERY_SCAN_UNAVAILABLE");
        }
        return recovered;
    }

    public boolean recover(long itemId) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var item = mapper.tryLockItem(itemId);
            if (item == null || item.state() != ItemState.PROCESSING || item.attemptId() == null) {
                return false;
            }
            var attempt = mapper.attempt(item.attemptId());
            if (!ImportAcceptanceService.owns(item, attempt) || mapper.now().isBefore(attempt.leaseUntil())) {
                return false;
            }
            ImportCreateService.requireOne(mapper.fail(itemId, attempt.id(), attempt.generation(), FailureCode.EXPIRED));
            ImportCreateService.requireOne(mapper.finish(attempt.id(), AttemptState.INVALIDATED.name()));
            return true;
        }));
    }
}
