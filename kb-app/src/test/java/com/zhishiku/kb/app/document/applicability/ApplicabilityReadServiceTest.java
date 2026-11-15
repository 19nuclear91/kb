package com.zhishiku.kb.app.document.applicability;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.applicability.mapper.ApplicabilityMapper;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityRow;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityTarget;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityChange;
import com.zhishiku.kb.app.document.applicability.model.UntilKind;
import com.zhishiku.kb.app.document.applicability.service.ApplicabilityReadService;
import com.zhishiku.kb.app.document.applicability.service.BusinessDate;
import com.zhishiku.kb.app.document.applicability.service.ValidityAccess;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.app.document.service.DocumentVersionAccessService;
import com.zhishiku.kb.common.conversation.SourceKey;
import com.zhishiku.kb.common.review.VersionAccessPurpose;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicabilityReadServiceTest {
    @Test
    void hiddenCandidateAndMismatchedOwnershipAreNotFoundForSingleAndHistoryReads() {
        var mapper = mock(ApplicabilityMapper.class);
        var permissions = mock(PermissionService.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        var versions = mock(DocumentVersionAccessService.class);
        when(permissions.principalOf(7)).thenReturn(handle);
        when(versions.visible(handle, Set.of(new SourceKey(10, 20)), VersionAccessPurpose.INTERACTIVE)).thenReturn(Set.of());
        var access = new ValidityAccess(permissions, versions, mapper, mock(SqlSessionTemplate.class));
        var read = new ApplicabilityReadService(mapper, access, versions, mock(BusinessDate.class));
        assertThatThrownBy(() -> read.get(7, 10, 20)).isInstanceOfSatisfying(ValidityException.class,
                error -> assertThat(error.reason()).isEqualTo(ValidityException.Reason.NOT_FOUND));
        assertThatThrownBy(() -> read.history(7, 10, 20, 20, null)).isInstanceOf(ValidityException.class);
        when(versions.visible(handle, Set.of(new SourceKey(10, 20)), VersionAccessPurpose.INTERACTIVE)).thenReturn(Set.of(new SourceKey(10, 20)));
        when(mapper.target(10, 20)).thenReturn(null);
        assertThatThrownBy(() -> read.get(7, 10, 20)).isInstanceOf(ValidityException.class);
    }

    @Test
    void missingDeclarationReturnsVersionZeroAndReviewFreezeWithoutInventedDates() {
        var mapper = mock(ApplicabilityMapper.class);
        var access = mock(ValidityAccess.class);
        var date = mock(BusinessDate.class);
        when(mapper.target(10, 20)).thenReturn(new ApplicabilityTarget(10, 20, 2, "active", false, "VERSIONED_V1", "NONE", true));
        when(mapper.get(10, 20)).thenReturn(new ApplicabilityRow(10, 20, null, UntilKind.UNDECLARED, null, 0, false, null, false));
        when(date.today()).thenReturn(LocalDate.of(2026, 11, 15));
        var detail = new ApplicabilityReadService(mapper, access, mock(DocumentVersionAccessService.class), date).get(7, 10, 20);
        assertThat(detail.applicabilityVersion()).isZero();
        assertThat(detail.reviewFrozen()).isTrue();
        assertThat(detail.canEdit()).isFalse();
        assertThat(detail.effectiveFrom()).isNull();
        assertThat(detail.effectiveUntil()).isNull();
    }

    @Test
    void historyUsesBoundedCursorAndRechecksAccessOnEveryPage() {
        var mapper = mock(ApplicabilityMapper.class);
        var access = mock(ValidityAccess.class);
        var date = mock(BusinessDate.class);
        var read = new ApplicabilityReadService(mapper, access, mock(DocumentVersionAccessService.class), date);
        var time = OffsetDateTime.parse("2026-11-15T00:00:00Z");
        var first = new ApplicabilityChange(2, 10, 20, time, null, UntilKind.UNDECLARED, null,
                null, UntilKind.OPEN_ENDED, null, true, "更正原因");
        var second = new ApplicabilityChange(1, 10, 20, time.minusSeconds(1), null, UntilKind.UNDECLARED, null,
                null, UntilKind.DATE, LocalDate.of(2026, 12, 31), false, null);
        when(mapper.history(10, 20, null, null, 2)).thenReturn(List.of(first, second));
        when(mapper.history(10, 20, time, 2L, 2)).thenReturn(List.of(second));
        when(date.today()).thenReturn(LocalDate.of(2026, 11, 15));
        var page = read.history(7, 10, 20, 1, null);
        assertThat(page.items()).containsExactly(first);
        assertThat(page.nextCursor()).isNotNull();
        assertThat(read.history(7, 10, 20, 1, page.nextCursor()).items()).containsExactly(second);
        assertThatThrownBy(() -> read.history(7, 11, 20, 1, page.nextCursor())).isInstanceOf(ValidityException.class);
    }
}
