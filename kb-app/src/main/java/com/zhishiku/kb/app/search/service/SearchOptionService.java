package com.zhishiku.kb.app.search.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.execution.SearchExecutor;
import com.zhishiku.kb.app.search.execution.SearchMetrics;
import com.zhishiku.kb.app.search.execution.SearchResponseEncoder;
import com.zhishiku.kb.app.search.execution.SearchTransaction;
import com.zhishiku.kb.app.search.model.SearchOptions;
import com.zhishiku.kb.common.security.PermissionScope;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Function;
import java.util.function.ToIntFunction;

/** 搜索候选共享准入与截止，但不调度模型或关键词检索。 */
@Service
public class SearchOptionService {
    private final PermissionService permissions;
    private final SearchOptionReader reader;
    private final SearchExecutor executor;
    private final SearchTransaction transaction;
    private final SearchResponseEncoder encoder;

    public SearchOptionService(PermissionService permissions, SearchOptionReader reader, SearchExecutor executor,
                               SearchTransaction transaction, SearchResponseEncoder encoder) {
        this.permissions = permissions;
        this.reader = reader;
        this.executor = executor;
        this.transaction = transaction;
        this.encoder = encoder;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public byte[] categories(long userId, SearchOptions.CategoryQuery query) {
        return execute(userId, SearchMetrics.Operation.CATEGORIES,
                scope -> reader.categories(userId, query, scope), page -> page.items().size());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public byte[] tags(long userId, SearchOptions.TagQuery query) {
        return execute(userId, SearchMetrics.Operation.TAGS,
                scope -> reader.tags(userId, query, scope), page -> page.items().size());
    }

    private <T> byte[] execute(long userId, SearchMetrics.Operation operation, Function<PermissionScope, T> action,
                               ToIntFunction<T> size) {
        return executor.execute(userId, operation, execution -> {
            try {
                PermissionScope scope = executor.required(execution, SearchBudget.Stage.AUTHORIZATION,
                        () -> {
                            var principal = permissions.principalOf(userId);
                            principal.requireEnabled();
                            return permissions.resolve(principal, PermissionService.PERM_VIEW);
                        });
                T page = executor.required(execution, SearchBudget.Stage.RETRIEVAL,
                        () -> transaction.executeSnapshot(() -> action.apply(scope)));
                execution.resultCount(size.applyAsInt(page));
                return executor.required(execution, SearchBudget.Stage.RESPONSE, () -> encoder.encodeValue(page));
            } catch (DataAccessException | TransactionException failure) {
                throw SearchService.databaseFailure(failure);
            }
        });
    }
}
