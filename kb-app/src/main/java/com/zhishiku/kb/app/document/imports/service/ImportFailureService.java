package com.zhishiku.kb.app.document.imports.service;

import com.zhishiku.kb.app.document.imports.mapper.ImportMapper;
import com.zhishiku.kb.app.document.imports.model.ImportModels.AttemptState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.FailureCode;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Attempt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 只处理尚未进入最终提交的确定失败；旧尝试结果不能覆盖当前执行。 */
@Service
public class ImportFailureService {
    private final ImportMapper mapper;
    private final TransactionTemplate transaction;

    public ImportFailureService(ImportMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    public boolean invalidate(Attempt execution, FailureCode code) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var item = mapper.lockItem(execution.itemId());
            var current = mapper.attempt(execution.id());
            if (!ImportAcceptanceService.owns(item, current) || !current.equals(execution)) {
                return false;
            }
            ImportCreateService.requireOne(mapper.fail(item.id(), current.id(), current.generation(), code));
            ImportCreateService.requireOne(mapper.finish(current.id(), AttemptState.INVALIDATED.name()));
            return true;
        }));
    }
}
