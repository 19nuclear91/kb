package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.DepartmentRecord;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DepartmentServiceTest {

    private IdentityDepartmentMapper mapper;
    private DepartmentService service;

    @BeforeEach
    void setUp() {
        mapper = mock(IdentityDepartmentMapper.class);
        service = new DepartmentService(mapper);
    }

    @Test
    void createsChildWithMaterializedPath() {
        DepartmentRecord parent = department(2L, null, "研发", "/1/2/", true, 3L);
        when(mapper.findDepartmentByCode("platform")).thenReturn(null);
        when(mapper.findDepartment(2L)).thenReturn(parent);
        when(mapper.nextDepartmentId()).thenReturn(4L);
        when(mapper.insertDepartment(any())).thenAnswer(invocation -> {
            DepartmentRecord created = invocation.getArgument(0);
            assertThat(created.getPath()).isEqualTo("/1/2/4/");
            return 1;
        });

        var result = service.create(" platform ", "平台组", 2L);

        assertThat(result.departmentId()).isEqualTo(4L);
        assertThat(result.path()).isEqualTo("/1/2/4/");
    }

    @Test
    void rejectsMoveIntoOwnDescendantWithoutWriting() {
        DepartmentRecord node = department(2L, 1L, "研发", "/1/2/", true, 3L);
        DepartmentRecord descendant = department(4L, 2L, "平台", "/1/2/4/", true, 1L);
        when(mapper.findDepartmentForUpdate(2L)).thenReturn(node);
        when(mapper.findDepartment(4L)).thenReturn(descendant);

        assertThatThrownBy(() -> service.move(2L, 4L, 3L))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("层级环");
        verifyNoInteractionsAfterLookup();
    }

    @Test
    void rejectsStaleVersionBeforePathRewrite() {
        DepartmentRecord node = department(2L, 1L, "研发", "/1/2/", true, 4L);
        when(mapper.findDepartmentForUpdate(2L)).thenReturn(node);

        assertThatThrownBy(() -> service.move(2L, null, 3L))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("其他操作更新");
        verify(mapper, Mockito.never())
                .rewriteDescendantPaths(anyLong(), any(), any(), any());
    }

    @Test
    void retriesReturnCurrentDepartmentWithoutRepeatingMutation() {
        IdentityAuditService audit = mock(IdentityAuditService.class);
        DepartmentService audited = new DepartmentService(mapper, audit);
        when(audit.existing(AuditScope.DEPARTMENT_MOVE, "request-1"))
                .thenReturn(new IdentityAuditRecord(null, null, "DEPARTMENT_MOVE", "request-1", 1L,
                        "department", 2L, "move", "SUCCESS", "{}", "{}", null, null));
        DepartmentRecord current = department(2L, 1L, "research", "/1/2/", true, 4L);
        when(mapper.findDepartment(2L)).thenReturn(current);

        assertThat(audited.move(2L, null, 4L, 1L, "request-1").departmentId()).isEqualTo(2L);
        verify(mapper, Mockito.never()).moveDepartment(anyLong(), any(), any(), anyLong());
    }

    @Test
    void duplicateCodeCarriesIdentifierReasonWithoutInsertOrAudit() {
        IdentityAuditService audit = mock(IdentityAuditService.class);
        DepartmentService audited = new DepartmentService(mapper, audit);
        when(mapper.findDepartmentByCode("platform")).thenReturn(department(4L, null, "platform", "/4/", true, 0L));

        assertThatThrownBy(() -> audited.create("platform", "平台组", null, 1L, "create-1"))
                .isInstanceOfSatisfying(IdentityException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(409);
                    assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.CONFLICT);
                    assertThat(e.reason()).contains(IdentityConflictReason.IDENTIFIER_CONFLICT);
                });
        verify(mapper, Mockito.never()).insertDepartment(any());
        verify(audit, Mockito.never()).success(any(), any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"1,0", "0,1"})
    void disablingNonEmptyDepartmentCarriesDependencyReason(long children, long users) {
        IdentityAuditService audit = mock(IdentityAuditService.class);
        DepartmentService audited = new DepartmentService(mapper, audit);
        when(mapper.findDepartmentForUpdate(2L)).thenReturn(department(2L, 1L, "研发", "/1/2/", true, 3L));
        when(mapper.countEnabledChildren(2L)).thenReturn(children);
        when(mapper.countEnabledUsers(2L)).thenReturn(users);

        assertThatThrownBy(() -> audited.setEnabled(2L, false, 3L, 1L, "disable-1"))
                .isInstanceOfSatisfying(IdentityException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(409);
                    assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.CONFLICT);
                    assertThat(e.reason()).contains(IdentityConflictReason.DEPARTMENT_NOT_EMPTY);
                });
        verify(mapper, Mockito.never()).setDepartmentEnabled(anyLong(), Mockito.anyBoolean(), anyLong());
        verify(audit, Mockito.never()).success(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void versionAndHierarchyConflictsKeepTheirOwnCodesWithoutReason() {
        when(mapper.findDepartmentForUpdate(2L)).thenReturn(department(2L, 1L, "研发", "/1/2/", true, 4L));
        assertThatThrownBy(() -> service.move(2L, null, 3L))
                .isInstanceOfSatisfying(IdentityException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.INVALID_VERSION);
                    assertThat(e.reason()).isEmpty();
                });
        when(mapper.findDepartment(4L)).thenReturn(department(4L, 2L, "平台", "/1/2/4/", true, 1L));
        assertThatThrownBy(() -> service.move(2L, 4L, 4L))
                .isInstanceOfSatisfying(IdentityException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.INVALID_HIERARCHY);
                    assertThat(e.reason()).isEmpty();
                });
    }

    private void verifyNoInteractionsAfterLookup() {
        verify(mapper, Mockito.never())
                .rewriteDescendantPaths(anyLong(), any(), any(), any());
        verify(mapper, Mockito.never())
                .moveDepartment(anyLong(), any(), any(), anyLong());
    }

    private static DepartmentRecord department(Long id, Long parentId, String code,
                                               String path, boolean enabled, long version) {
        return new DepartmentRecord(id, parentId, code, code, path, enabled, version, null, null);
    }
}
