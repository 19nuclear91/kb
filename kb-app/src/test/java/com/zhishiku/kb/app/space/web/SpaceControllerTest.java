package com.zhishiku.kb.app.space.web;

import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.space.service.SpaceAclService;
import com.zhishiku.kb.app.space.service.SpaceAuthorizer;
import com.zhishiku.kb.app.space.service.SpaceQueryService;
import com.zhishiku.kb.app.space.service.SpaceWriteService;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PrincipalType;
import com.zhishiku.kb.common.security.SpaceAclGrantResult;
import com.zhishiku.kb.common.security.SpaceAclSummary;
import com.zhishiku.kb.common.security.SpaceSummary;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 空间管理接口的错误语义：403 / 404 / 409 必须如实返回，
 * 而不是被全局处理器折叠成 500——{@code IdentityException} 自带 httpStatus。
 */
class SpaceControllerTest {

    private final SpaceQueryService queries = mock(SpaceQueryService.class);
    private final SpaceWriteService writes = mock(SpaceWriteService.class);
    private final SpaceAclService acls = mock(SpaceAclService.class);
    private final SpaceAuthorizer authorizer = mock(SpaceAuthorizer.class);

    private MockMvc mvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders
                .standaloneSetup(new SpaceController(queries, writes, acls, authorizer))
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void unauthorizedWriteReturnsForbiddenAndDoesNotReachService() throws Exception {
        doThrow(IdentityException.forbidden("无权管理该空间"))
                .when(writes).updateMetadata(anyLong(), anyString(), any(), anyLong(), any(), anyString());

        mvc().perform(patch("/api/admin/spaces/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-1")
                        .content("{\"name\":\"新名\",\"description\":null,\"version\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingRequestIdIsBadRequest() throws Exception {
        mvc().perform(patch("/api/admin/spaces/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名\",\"description\":null,\"version\":0}"))
                .andExpect(status().isBadRequest());

        verify(writes, never()).updateMetadata(anyLong(), anyString(), any(), anyLong(), any(), anyString());
    }

    @Test
    void staleVersionSurfacesAsConflict() throws Exception {
        when(writes.updateMetadata(eq(3L), anyString(), any(), eq(1L), any(), eq("req-2")))
                .thenThrow(IdentityException.conflict(
                        IdentityErrorCode.INVALID_VERSION,
                        "空间已被其他操作更新"));

        mvc().perform(patch("/api/admin/spaces/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-2")
                        .content("{\"name\":\"新名\",\"description\":null,\"version\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void deletingSpaceWithDocumentsSurfacesAsConflict() throws Exception {
        doThrow(IdentityException.conflict(
                IdentityErrorCode.CONFLICT,
                "空间仍有 4 篇文档，不能删除，请改用归档"))
                .when(writes).delete(eq(3L), ArgumentMatchers.<Long>any(), eq("req-3"));

        mvc().perform(delete("/api/admin/spaces/3")
                        .header("X-Request-Id", "req-3"))
                .andExpect(status().isConflict());
    }

    @Test
    void hiddenSpaceSurfacesAsNotFound() throws Exception {
        when(authorizer.requireEnabledUser()).thenReturn(7L);
        when(queries.get(7L, 99L))
                .thenThrow(IdentityException.notFound("空间不存在或当前用户无权访问"));

        mvc().perform(get("/api/admin/spaces/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void archiveReturnsUpdatedSummaryWithArchivedFlag() throws Exception {
        when(writes.archive(eq(3L), eq(2L), any(), eq("req-4")))
                .thenReturn(new SpaceSummary(
                        3L, "ops", "运维", null, true, 3L, false, true));

        mvc().perform(post("/api/admin/spaces/3/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-4")
                        .content("{\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.archived").value(true))
                .andExpect(jsonPath("$.data.viewable").value(false))
                .andExpect(jsonPath("$.data.manageable").value(true));
    }

    @Test
    void grantingWhitelistedPermissionReturnsReceipt() throws Exception {
        when(acls.grant(eq(1L), eq(PrincipalType.DEPT), eq(2L),
                eq("edit"), any(), eq("req-5")))
                .thenReturn(new SpaceAclGrantResult(
                        new SpaceAclSummary(
                                9L, 1L, PrincipalType.DEPT,
                                2L, "3:2", "研发中心", "edit"),
                        false, 1L));

        mvc().perform(post("/api/admin/spaces/1/acl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-5")
                        .content("{\"principalType\":\"DEPT\",\"principalId\":2,\"permission\":\"edit\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyGranted").value(false))
                .andExpect(jsonPath("$.data.clearanceBlockedPrincipalCount").value(1));
    }

    @Test
    void grantingUnexecutedPermissionBitReturnsBadRequest() throws Exception {
        when(acls.grant(eq(1L), any(), eq(2L), eq("approve"), any(), eq("req-6")))
                .thenThrow(IdentityException.badRequest(
                        "权限位 approve 不被支持，可授予的权限位仅为 view、download、edit、review、comment（manage 需 space:grant-manage）"));

        mvc().perform(post("/api/admin/spaces/1/acl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-6")
                        .content("{\"principalType\":\"DEPT\",\"principalId\":2,\"permission\":\"approve\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void grantingManageBySpaceManagerReturnsForbidden() throws Exception {
        when(acls.grant(eq(1L), any(), eq(2L), eq("manage"), any(), eq("req-7")))
                .thenThrow(IdentityException.forbidden("该操作仅限全局身份管理员"));

        mvc().perform(post("/api/admin/spaces/1/acl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-7")
                        .content("{\"principalType\":\"DEPT\",\"principalId\":2,\"permission\":\"manage\"}"))
                .andExpect(status().isForbidden());
    }

    // 注：缺少 principalType 这类 Bean Validation 失败目前会被
    // GlobalExceptionHandler 的兜底分支折叠成 500——这是项目既有的全局缺口
    // （见《架构决策记录》里"业务错误码统一折叠"的待办），不属于本变更范围。
    // 这里不假装它返回 400；显式抛出的 IdentityException 才携带真实状态码。
}
