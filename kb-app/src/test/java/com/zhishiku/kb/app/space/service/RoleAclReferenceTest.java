package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.RoleRecord;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.space.mapper.DocumentAclMapper;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PrincipalType;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyShort;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleAclReferenceTest {
    @Test
    void referenceLockRequiresAnExistingRoleAndSurvivesUntilCallerTransactionCommits() throws Exception {
        var mapper = mock(IdentityDepartmentMapper.class);
        var service = new IdentityService(mapper);
        assertThatThrownBy(() -> service.requireRoleReference(2L)).isInstanceOf(IdentityException.class);
        when(mapper.findRoleForReference(2L)).thenReturn(new RoleRecord(2L, "ROLE_TEST", "测试"));
        assertThatCode(() -> service.requireRoleReference(2L)).doesNotThrowAnyException();
        assertThat(IdentityService.class.getMethod("requireRoleReference", long.class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(String.join(" ", IdentityDepartmentMapper.class.getMethod("findRoleForReference", long.class)
                .getAnnotation(Select.class).value())).contains("FOR KEY SHARE");
        assertThat(String.join(" ", RolePermissionMapper.class.getMethod("lockRole", long.class)
                .getAnnotation(Select.class).value())).contains("FOR UPDATE");
        assertThat(String.join(" ", RolePermissionMapper.class.getMethod("countAclReferences", long.class)
                .getAnnotation(Select.class).value())).contains("kb_space_acl", "kb_document_acl", "principal_type = " +
                "2", "principal_id = #{roleId}");
    }

    @Test
    void bothAclWritersLockRoleBeforeInsertingAndRejectMissingRoles() {
        var authorizer = mock(SpaceAuthorizer.class);
        var spaces = mock(SpaceMapper.class);
        var documents = mock(DocumentAclMapper.class);
        when(documents.findOwningSpaceId(10L)).thenReturn(1L);
        var spaceService = new SpaceAclService(spaces, authorizer);
        var documentService = new DocumentAclService(documents, authorizer);
        spaceService.grant(1L, PrincipalType.ROLE, 2L, "view", 7L, "space");
        documentService.grant(10L, PrincipalType.ROLE, 2L, "view", 7L, "document");
        var order = inOrder(authorizer, spaces, documents);
        order.verify(authorizer).requireRoleReference(2L);
        order.verify(spaces).grantAcl(1L, PrincipalType.ROLE.code(), 2L, "view");
        order.verify(authorizer).requireRoleReference(2L);
        order.verify(documents).grantAcl(10L, PrincipalType.ROLE.code(), 2L, "view");
        clearInvocations(spaces, documents);
        doThrow(IdentityException.badRequest("授权主体角色不存在")).when(authorizer).requireRoleReference(2L);
        assertThatThrownBy(() -> spaceService.grant(1L, PrincipalType.ROLE, 2L, "view", 7L, "space2"))
                .isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> documentService.grant(10L, PrincipalType.ROLE, 2L, "view", 7L, "document2"))
                .isInstanceOf(IdentityException.class);
        verify(spaces, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
        verify(documents, never()).grantAcl(anyLong(), anyShort(), anyLong(), anyString());
    }
}
