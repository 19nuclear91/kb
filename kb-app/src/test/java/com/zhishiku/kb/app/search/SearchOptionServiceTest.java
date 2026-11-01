package com.zhishiku.kb.app.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.execution.SearchMetrics;
import com.zhishiku.kb.app.search.execution.SearchResponseEncoder;
import com.zhishiku.kb.app.search.execution.SearchTransaction;
import com.zhishiku.kb.app.search.mapper.SearchOptionMapper;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.model.SearchOptions;
import com.zhishiku.kb.app.search.service.SearchOptionReader;
import com.zhishiku.kb.app.search.service.SearchOptionService;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchOptionServiceTest {
    @Test
    void eachReadResolvesCurrentIdentityAndUsesSnapshotThenBoundedResponse() throws Exception {
        var permissions = mock(PermissionService.class);
        var first = mock(PermissionService.PrincipalHandle.class);
        var second = mock(PermissionService.PrincipalHandle.class);
        when(permissions.principalOf(7)).thenReturn(first, second);
        var scope = PermissionScope.of(7, (short) 2, List.of(1L), List.of("1:7"));
        when(permissions.resolve(first, "view")).thenReturn(scope);
        when(permissions.resolve(second, "view")).thenReturn(PermissionScope.denyAll(7));
        var mapper = mock(SearchOptionMapper.class);
        when(mapper.tags(null, null, 21, List.of(1L), List.of("1:7"), (short) 2))
                .thenReturn(List.of(new SearchOptions.Tag(8, "visible", false)));
        var transaction = mock(SearchTransaction.class);
        when(transaction.executeSnapshot(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        var executor = SearchExecutionStub.immediate();
        var json = new ObjectMapper();
        var service = new SearchOptionService(permissions, new SearchOptionReader(mapper), executor, transaction,
                new SearchResponseEncoder(json, SearchLimitsProperties.defaults()));
        var query = new SearchOptions.TagQuery(null, 20, null);
        assertThat(json.readTree(service.tags(7, query)).path("data").path("items").size()).isEqualTo(1);
        assertThat(json.readTree(service.tags(7, query)).path("data").path("items").size()).isZero();
        verify(permissions, times(2)).principalOf(7);
        verify(second).requireEnabled();
        verify(permissions).resolve(second, "view");
        verify(mapper).tags(null, null, 21, List.of(1L), List.of("1:7"), (short) 2);
        verify(executor, times(2)).execute(eq(7L), eq(SearchMetrics.Operation.TAGS), any());
        verify(executor, never()).optional(any(), any(), any());
        var order = inOrder(permissions, first, transaction, mapper, executor);
        order.verify(executor).execute(eq(7L), eq(SearchMetrics.Operation.TAGS), any());
        order.verify(executor).required(any(), eq(SearchBudget.Stage.AUTHORIZATION), any());
        order.verify(permissions).principalOf(7);
        order.verify(first).requireEnabled();
        order.verify(permissions).resolve(first, "view");
        order.verify(executor).required(any(), eq(SearchBudget.Stage.RETRIEVAL), any());
        order.verify(transaction).executeSnapshot(any());
        order.verify(mapper).tags(null, null, 21, List.of(1L), List.of("1:7"), (short) 2);
        order.verify(executor).required(any(), eq(SearchBudget.Stage.RESPONSE), any());
    }

    @Test
    void failedSnapshotAndOversizedProjectionAreNotSuccessfulEmptyPages() {
        var permissions = mock(PermissionService.class);
        var principal = mock(PermissionService.PrincipalHandle.class);
        when(permissions.principalOf(7)).thenReturn(principal);
        when(permissions.resolve(principal, "view")).thenReturn(PermissionScope.denyAll(7));
        var transaction = mock(SearchTransaction.class);
        when(transaction.executeSnapshot(any())).thenThrow(new CannotCreateTransactionException("private"));
        var service = new SearchOptionService(permissions, mock(SearchOptionReader.class), SearchExecutionStub.immediate(),
                transaction, new SearchResponseEncoder(new ObjectMapper(), SearchLimitsProperties.defaults()));
        assertThatThrownBy(() -> service.categories(7, new SearchOptions.CategoryQuery(1, null, 20, null)))
                .isInstanceOfSatisfying(SearchFailure.class, error -> assertThat(error.status()).isEqualTo(503));
        var limits = new SearchLimitsProperties(8000, 128, 128, 128, 128, 32);
        assertThatThrownBy(() -> new SearchResponseEncoder(new ObjectMapper(), limits)
                .encodeValue(new SearchOptions.TagPage(List.of(new SearchOptions.Tag(1, "x".repeat(100), true)), false, null)))
                .isInstanceOf(SearchFailure.class);
    }
}
