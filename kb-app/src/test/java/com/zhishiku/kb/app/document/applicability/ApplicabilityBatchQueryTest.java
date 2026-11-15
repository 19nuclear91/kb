package com.zhishiku.kb.app.document.applicability;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.applicability.mapper.ApplicabilityMapper;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityRow;
import com.zhishiku.kb.app.document.applicability.model.UntilKind;
import com.zhishiku.kb.app.document.applicability.service.ApplicabilityReadService;
import com.zhishiku.kb.app.document.applicability.service.BusinessDate;
import com.zhishiku.kb.app.document.applicability.service.ValidityAccess;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.app.document.service.DocumentVersionAccessService;
import com.zhishiku.kb.common.conversation.SourceKey;
import com.zhishiku.kb.common.review.VersionAccessPurpose;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;

class ApplicabilityBatchQueryTest {
    @Test
    void invisibleItemsAreOmittedAndCurrentIsBoundToExactVersionWithoutAnyWrite() {
        var mapper = mock(ApplicabilityMapper.class);
        var access = mock(ValidityAccess.class);
        var versions = mock(DocumentVersionAccessService.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        var date = mock(BusinessDate.class);
        var first = new SourceKey(10, 20);
        var second = new SourceKey(10, 21);
        var hidden = new SourceKey(11, 22);
        when(access.current(7)).thenReturn(handle);
        when(versions.visible(handle, Set.of(first, second, hidden), VersionAccessPurpose.INTERACTIVE)).thenReturn(Set.of(first, second));
        when(date.today()).thenReturn(LocalDate.of(2026, 11, 15));
        when(mapper.batch(List.of(first, second))).thenReturn(List.of(
                new ApplicabilityRow(10, 20, null, UntilKind.DATE, LocalDate.of(2026, 11, 14), 1, true, null, false),
                new ApplicabilityRow(10, 21, null, UntilKind.UNDECLARED, null, 0, false, null, true)));
        var read = new ApplicabilityReadService(mapper, access, versions, date);
        var result = read.batch(7, List.of(first, second, hidden));
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().getFirst().current()).isFalse();
        assertThat(result.items().getLast().current()).isTrue();
        assertThat(result.items().getFirst().state().name()).isEqualTo("EXPIRED");
        verify(mapper).batch(List.of(first, second));
        verify(mapper, never()).insert(any());
        verify(mapper, never()).update(any());
        verify(mapper, never()).appendChange(any());
    }

    @Test
    void emptyOversizedRepeatedAndInvalidPairsAreRejectedBeforeAnyReads() {
        var mapper = mock(ApplicabilityMapper.class);
        var access = mock(ValidityAccess.class);
        var versions = mock(DocumentVersionAccessService.class);
        var date = mock(BusinessDate.class);
        var read = new ApplicabilityReadService(mapper, access, versions, date);
        for (List<SourceKey> keys : List.of(List.<SourceKey>of(), List.of(new SourceKey(1, 2), new SourceKey(1, 2)),
                IntStream.rangeClosed(1, 101).mapToObj(id -> new SourceKey(id, id)).toList())) {
            assertThatThrownBy(() -> read.batch(7, keys)).isInstanceOfSatisfying(ValidityException.class,
                    error -> assertThat(error.reason()).isEqualTo(ValidityException.Reason.INVALID_INPUT));
        }
        verifyNoInteractions(mapper, access, versions, date);
    }

    @Test
    void accessAndDataFailuresAre503NotEmptyPages() {
        var mapper = mock(ApplicabilityMapper.class);
        var access = mock(ValidityAccess.class);
        var versions = mock(DocumentVersionAccessService.class);
        var date = mock(BusinessDate.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        var key = new SourceKey(10, 20);
        var read = new ApplicabilityReadService(mapper, access, versions, date);
        when(access.current(7)).thenReturn(handle);
        when(versions.visible(handle, Set.of(key), VersionAccessPurpose.INTERACTIVE)).thenThrow(new IllegalStateException("private"));
        assertThatThrownBy(() -> read.batch(7, List.of(key))).isInstanceOfSatisfying(ValidityException.class,
                error -> assertThat(error.reason().status()).isEqualTo(503));
        org.mockito.Mockito.doReturn(Set.of(key)).when(versions).visible(handle, Set.of(key), VersionAccessPurpose.INTERACTIVE);
        when(date.today()).thenReturn(LocalDate.of(2026, 11, 15));
        when(mapper.batch(List.of(key))).thenReturn(null);
        assertThatThrownBy(() -> read.batch(7, List.of(key))).isInstanceOfSatisfying(ValidityException.class,
                error -> assertThat(error.reason().status()).isEqualTo(503));
    }
}
