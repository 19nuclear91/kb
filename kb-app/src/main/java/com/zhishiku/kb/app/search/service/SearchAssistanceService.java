package com.zhishiku.kb.app.search.service;

import com.zhishiku.kb.app.search.assistance.AssistedCaller;
import com.zhishiku.kb.app.search.assistance.SearchContextFailure;
import com.zhishiku.kb.app.search.assistance.SearchSelectionContext;
import com.zhishiku.kb.app.search.execution.SearchContext;
import com.zhishiku.kb.app.search.expansion.SearchQueryPlan;
import com.zhishiku.kb.app.search.expansion.SearchQueryPlanner;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.term.TermSnapshotReader;
import com.zhishiku.kb.app.search.web.AssistedSearchRequest;
import org.springframework.stereotype.Service;

/** 词典阶段只返回不可变计划及说明，数据库回调与原令牌不跨越模型调用。 */
@Service
public class SearchAssistanceService {
    public record Prepared(SearchQueryPlan plan, SearchQueryPlan.Assistance assistance) {
        @Override
        public String toString() {
            return "PreparedSearchAssistance[redacted]";
        }
    }

    private final TermSnapshotReader reader;
    private final SearchSelectionContext contexts;

    public SearchAssistanceService(TermSnapshotReader reader, SearchSelectionContext contexts) {
        this.reader = reader;
        this.contexts = contexts;
    }

    public Prepared prepare(AssistedCaller caller, AssistedSearchRequest body, int maxCodepoints) {
        SearchContext context = SearchContext.current();
        if (context == null) {
            throw new IllegalStateException("辅助阶段缺少搜索预算");
        }
        try {
            context.check();
            // 临时 Binding 仅在该调用表达式中存活，后续回调只持有 HMAC 摘要。
            var binding = contexts.prepare(new SearchSelectionContext.Binding(caller.userId(), caller.authEpoch(), caller.consumeToken(),
                    body.base().query(), body.base().filters(), body.candidates(), body.topK()));
            context.check();
            return reader.read(snapshot -> {
                context.check();
                var state = snapshot.state();
                if (body.selectionContext() != null) {
                    contexts.verifyPrepared(body.selectionContext(), binding, state.revision(), state.databaseTime().toInstant());
                }
                context.check();
                var plan = new SearchQueryPlanner().plan(body.mode(), body.base().query(), maxCodepoints, body.selections(), snapshot, context::check);
                String credential = plan.suggestions().isEmpty() ? null : contexts.issuePrepared(binding, state.revision(), state.databaseTime().toInstant());
                context.check();
                return new Prepared(plan, plan.assistance(credential));
            });
        } catch (SearchContextFailure failure) {
            throw new SearchFailure(switch (failure.kind()) {
                case INVALID_INPUT -> SearchFailure.Kind.INVALID_INPUT;
                case ASSISTANCE_STALE, DICTIONARY_CHANGED -> SearchFailure.Kind.ASSISTANCE_STALE;
                case ASSISTANCE_UNAVAILABLE -> SearchFailure.Kind.ASSISTANCE_UNAVAILABLE;
            });
        } finally {
            caller.discardToken();
        }
    }
}
