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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentVersionAccessServiceTest {
    @Test
    void usesRequestHandleAndOriginalVersionAcrossPermissionChangesWithoutChunkLookup() {
        var permissions = mock(PermissionService.class);
        var mapper = mock(DocumentMapper.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        var service = new DocumentVersionAccessService(mapper, permissions);
        var key = new SourceKey(1, 2);
        // 无空间授权但有文档例外主体，仍必须查询同一授权谓词。
        when(permissions.resolve(handle, "view")).thenReturn(PermissionScope.of(7, (short) 3, List.of(),
                Set.of("1:7")));
        when(mapper.visibleVersions(any(), eq(List.of()), eq(List.of("1:7")), eq((short) 3), eq(VersionAccessPurpose.MODEL_SOURCE)))
                .thenReturn(List.of(key), List.of(), List.of(), List.of(), List.of(key));
        assertThat(service.visible(handle, Set.of(key))).containsExactly(key);
        // 当前 ACL、归档、搬迁或版本归属变化由生产查询返回不可见；这里只验证服务失败关闭。
        for (int i = 0; i < 3; i++) assertThat(service.visible(handle, Set.of(key))).isEmpty();
        assertThat(service.visible(handle, Set.of(key))).containsExactly(key);
        verify(mapper, never()).findCitation(anyLong(), anyLong(), anyLong(), any(), any(), anyShort());
        when(permissions.resolve(handle, "view")).thenReturn(PermissionScope.denyAll(7));
        assertThat(service.visible(handle, Set.of(key))).isEmpty();
        when(permissions.resolve(handle, "view")).thenThrow(new IllegalStateException("secret"));
        assertThatThrownBy(() -> service.visible(handle, Set.of(key))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.httpStatus()).isEqualTo(503));
    }
}
