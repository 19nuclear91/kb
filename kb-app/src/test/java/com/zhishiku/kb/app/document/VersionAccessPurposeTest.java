package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentMapper;
import com.zhishiku.kb.app.document.service.DocumentVersionAccessService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.conversation.SourceKey;
import com.zhishiku.kb.common.review.VersionAccessPurpose;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VersionAccessPurposeTest {
    @Test
    void batchForInteractiveReviewerDoesNotBecomeModelOrCommentSource() {
        var mapper = mock(DocumentMapper.class);
        var permissions = mock(PermissionService.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        var service = new DocumentVersionAccessService(mapper, permissions);
        var key = new SourceKey(10, 20);
        when(permissions.resolve(handle, "view")).thenReturn(PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7")));
        when(mapper.visibleVersions(any(), any(), any(), eq((short) 2), any())).thenAnswer(call ->
                call.getArgument(4) == VersionAccessPurpose.INTERACTIVE ? List.of(key) : List.of());
        assertThat(service.visible(handle, Set.of(key), VersionAccessPurpose.INTERACTIVE)).containsExactly(key);
        assertThat(service.visible(handle, Set.of(key), VersionAccessPurpose.MODEL_SOURCE)).isEmpty();
        assertThat(service.visible(handle, Set.of(key), VersionAccessPurpose.COMMENT_TARGET)).isEmpty();
        when(permissions.resolve(handle, "view")).thenReturn(PermissionScope.denyAll(7));
        assertThat(service.visible(handle, Set.of(key), VersionAccessPurpose.INTERACTIVE)).isEmpty();
    }

    @Test
    void missingOrUnexpectedBatchProjectionIsDependencyFailureNotAuthorization() {
        var mapper = mock(DocumentMapper.class);
        var permissions = mock(PermissionService.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        var service = new DocumentVersionAccessService(mapper, permissions);
        var key = new SourceKey(10, 20);
        when(permissions.resolve(handle, "view")).thenReturn(PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7")));
        when(mapper.visibleVersions(any(), any(), any(), eq((short) 2), any())).thenReturn(null, List.of(new SourceKey(10, 21)));
        assertThatThrownBy(() -> service.visible(handle, Set.of(key))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.visible(handle, Set.of(key))).isInstanceOf(ApiException.class);
    }
}
