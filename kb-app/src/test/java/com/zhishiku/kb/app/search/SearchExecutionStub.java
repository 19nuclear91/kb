package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.execution.SearchExecutor;
import com.zhishiku.kb.app.search.execution.SearchContext;

import java.util.function.Function;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 编排单测的执行依赖替身；执行器生命周期由独立单测覆盖。 */
final class SearchExecutionStub {
    private SearchExecutionStub() {
    }

    static SearchExecutor immediate() {
        var executor = mock(SearchExecutor.class);
        var execution = mock(SearchExecutor.Execution.class);
        when(executor.execute(anyLong(), any())).thenAnswer(call -> {
            Function<SearchExecutor.Execution, Object> action = call.getArgument(1);
            return action.apply(execution);
        });
        when(executor.execute(anyLong(), any(), any())).thenAnswer(call -> {
            Function<SearchExecutor.Execution, Object> action = call.getArgument(2);
            return action.apply(execution);
        });
        when(executor.required(any(), any(), any())).thenAnswer(call -> {
            Supplier<Object> action = call.getArgument(2);
            return action.get();
        });
        when(executor.optional(any(), any(), any())).thenAnswer(call -> {
            Supplier<Object> action = call.getArgument(2);
            try (var current = mockStatic(SearchContext.class)) {
                current.when(SearchContext::current).thenReturn(mock(SearchContext.class));
                return action.get();
            }
        });
        return executor;
    }
}
