package com.zhishiku.kb.app.document.metadata;

import com.zhishiku.kb.app.document.metadata.mapper.MetadataQueryMapper;
import com.zhishiku.kb.app.document.metadata.mapper.TagMapper;
import com.zhishiku.kb.app.document.metadata.model.MetadataInputs;
import com.zhishiku.kb.app.document.metadata.model.MetadataSummary;
import com.zhishiku.kb.app.document.metadata.model.DocumentTagRow;
import com.zhishiku.kb.app.document.metadata.service.CategoryService;
import com.zhishiku.kb.app.document.metadata.service.DocumentFileTypes;
import com.zhishiku.kb.app.document.metadata.service.MetadataAccess;
import com.zhishiku.kb.app.document.metadata.service.MetadataQueryService;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class MetadataQueryTest {
    @Test
    void typesUseOnlyCurrentVersionAndConsistentFilenameMimePairs() {
        assertThat(DocumentFileTypes.classify(null, "file.pdf", "application/pdf")).isEqualTo(MetadataInputs.FileType.UNPUBLISHED);
        assertThat(DocumentFileTypes.classify(1L, "file.md", "text/plain")).isEqualTo(MetadataInputs.FileType.MARKDOWN);
        assertThat(DocumentFileTypes.classify(1L, "file.TXT", "text/plain")).isEqualTo(MetadataInputs.FileType.TXT);
        assertThat(DocumentFileTypes.classify(1L, "file.docx", "application/pdf")).isEqualTo(MetadataInputs.FileType.OTHER);
        assertThat(DocumentFileTypes.classify(1L, "file.pdf", "application/pdf")).isEqualTo(MetadataInputs.FileType.PDF);
    }

    @Test
    void countItemsAndTaxonomyUseSameRequestScopeAndFailuresPropagate() {
        var mapper = mock(MetadataQueryMapper.class);
        var access = mock(MetadataAccess.class);
        var tags = mock(TagMapper.class);
        var service = new MetadataQueryService(mapper, access, mock(CategoryService.class), tags);
        when(access.view()).thenReturn(PermissionScope.of(7, (short) 1, List.of(), List.of("1:7")));
        var query = new MetadataInputs.Query("%", null, null, false, List.of(1L, 2L), null, null, null, 1, 20);
        var item = new MetadataSummary();
        item.setId(9L);
        when(mapper.count(eq(query), eq("%\\%%"), anyList(), eq(List.of()), eq(List.of("1:7")), eq((short) 1))).thenReturn(1L);
        when(mapper.list(eq(query), any(), anyList(), anyList(), anyList(), anyShort())).thenReturn(List.of(item));
        when(tags.forDocuments(List.of(9L))).thenReturn(List.of(new DocumentTagRow(9, 1, "主题", false)));
        var result = service.query(query);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items().getFirst().getTags().getFirst().enabled()).isFalse();
        var order = inOrder(mapper, tags);
        order.verify(mapper).budget();
        order.verify(mapper).count(eq(query), any(), anyList(), anyList(), anyList(), anyShort());
        order.verify(mapper).list(eq(query), any(), anyList(), anyList(), anyList(), anyShort());
        order.verify(tags).forDocuments(List.of(9L));
        when(mapper.count(eq(query), any(), anyList(), anyList(), anyList(), anyShort())).thenThrow(new IllegalStateException("超时"));
        assertThatThrownBy(() -> service.query(query)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void transactionCollaboratorEnclosesAllReadsWithBudgetAndPropagatesTimeout() {
        var mapper = mock(MetadataQueryMapper.class);
        var access = mock(MetadataAccess.class);
        when(access.view()).thenReturn(PermissionScope.of(7, (short) 1, List.of(), List.of("1:7")));
        var transactions = mock(PlatformTransactionManager.class);
        var first = new SimpleTransactionStatus();
        var second = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(first, second);
        var factory = new ProxyFactory(new MetadataQueryService(mapper, access, mock(CategoryService.class), mock(TagMapper.class)));
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var service = (MetadataQueryService) factory.getProxy();
        var query = new MetadataInputs.Query(null, null, null, false, List.of(), null, null, null, 1, 20);
        assertThat(service.query(query).total()).isZero();
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        var order = inOrder(transactions, mapper, access);
        order.verify(transactions).getTransaction(definition.capture());
        order.verify(mapper).budget();
        order.verify(access).view();
        order.verify(mapper).count(eq(query), any(), anyList(), anyList(), anyList(), anyShort());
        order.verify(transactions).commit(first);
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(definition.getValue().getTimeout()).isEqualTo(5);
        when(mapper.budget()).thenThrow(new QueryTimeoutException("预算耗尽"));
        assertThatThrownBy(() -> service.query(query)).isInstanceOf(QueryTimeoutException.class);
        verify(transactions).rollback(second);
    }
}
