package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.search.term.mapper.TermMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** 管理域的独立短事务；搜索快照另用既有 SearchTransaction 的动态截止。 */
@Component
public class TermDatabase {
    private final PlatformTransactionManager manager;
    private final TermMapper mapper;

    public TermDatabase(PlatformTransactionManager manager, TermMapper mapper) {
        this.manager = manager;
        this.mapper = mapper;
    }

    public <T> T read(Supplier<T> action) {
        return execute(action, true);
    }

    public <T> T write(Supplier<T> action) {
        return execute(action, false);
    }

    private <T> T execute(Supplier<T> action, boolean readOnly) {
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setReadOnly(readOnly);
        transaction.setIsolationLevel(readOnly ? TransactionDefinition.ISOLATION_REPEATABLE_READ : TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
        try {
            return transaction.execute(status -> {
                mapper.manageTimeouts();
                return action.get();
            });
        } catch (DataAccessException | TransactionException unavailable) {
            throw new TermException(TermException.Reason.UNAVAILABLE);
        }
    }
}
