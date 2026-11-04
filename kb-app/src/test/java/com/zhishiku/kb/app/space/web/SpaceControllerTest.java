package com.zhishiku.kb.app.space.web;

import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.space.model.SpaceAclBatchGrantResult;
import com.zhishiku.kb.app.space.model.SpaceAclBatchRevokeItem;
import com.zhishiku.kb.app.space.model.SpaceAclBatchRevokeResult;
import com.zhishiku.kb.app.space.model.SpaceAclPrincipal;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Test
    void batchRouteBindsExactIdsAndReturnsItemizedReceiptIgnoringClearanceFields() throws Exception {
        var principals = List.of(new SpaceAclPrincipal(PrincipalType.USER, Long.MAX_VALUE),
                new SpaceAclPrincipal(PrincipalType.DEPT, 2));
        var permissions = List.of("view", "download");
        var result = new SpaceAclBatchGrantResult(1L,
                List.of(new SpaceAclBatchGrantResult.Item(PrincipalType.USER, Long.MAX_VALUE, "view", true),
                        new SpaceAclBatchGrantResult.Item(PrincipalType.USER, Long.MAX_VALUE, "download", false),
                        new SpaceAclBatchGrantResult.Item(PrincipalType.DEPT, 2, "view", false),
                        new SpaceAclBatchGrantResult.Item(PrincipalType.DEPT, 2, "download", false)), 3, 1, 1, false);
        when(acls.grantBatch(1L, principals, permissions, "batch")).thenReturn(result);
        mvc().perform(post("/api/admin/spaces/1/acl/batch")
                        .contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "batch")
                        .content("""
                                {"principals":[{"principalType":"USER","principalId":9223372036854775807,"clearanceLevel":9},
                                {"principalType":"DEPT","principalId":2}],"permissions":["view","download"],"clearanceLevel":9}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(4))
                .andExpect(jsonPath("$.data.items[0].principalId").value(Long.MAX_VALUE))
                .andExpect(jsonPath("$.data.items[0].alreadyGranted").value(true))
                .andExpect(jsonPath("$.data.items[1].alreadyGranted").value(false))
                .andExpect(jsonPath("$.data.grantedCount").value(3))
                .andExpect(jsonPath("$.data.alreadyGrantedCount").value(1))
                .andExpect(jsonPath("$.data.clearanceBlockedPrincipalCount").value(1))
                .andExpect(jsonPath("$.data.replayed").value(false));
        verify(acls).grantBatch(1L, principals, permissions, "batch");
        verify(acls, never()).grant(anyLong(), any(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    void batchMissingOrBlankHeaderCannotBeReplacedByBodyRequestId() throws Exception {
        var mvc = mvc();
        String body = """
                {"principals":[{"principalType":"USER","principalId":2}],"permissions":["view"],"requestId":"body"}
                """;
        mvc.perform(post("/api/admin/spaces/1/acl/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/spaces/1/acl/batch").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", " ").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(acls);
    }

    @Test
    void batchInvalidPathAndMalformedBindingAreBadRequests() throws Exception {
        var mvc = mvc();
        String body = """
                {"principals":[{"principalType":"USER","principalId":2}],"permissions":["view"]}
                """;
        for (String id : List.of("0", "-1", "abc", "1.5", "9223372036854775808")) {
            mvc.perform(post("/api/admin/spaces/" + id + "/acl/batch").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Request-Id", "batch").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/admin/spaces/1/acl/batch").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "batch").content(body.replace("USER", "UNKNOWN")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(acls);
    }

    @Test
    void revokeBatchBindsExplicitEntriesAndReturnsExactIdsAndRemovalReceipt() throws Exception {
        var items = List.of(new SpaceAclBatchRevokeItem(PrincipalType.USER, Long.MAX_VALUE, "view"),
                new SpaceAclBatchRevokeItem(PrincipalType.DEPT, 2, "download"));
        when(acls.revokeBatch(1L, items, "header-id")).thenReturn(new SpaceAclBatchRevokeResult(1L, List.of(
                new SpaceAclBatchRevokeResult.Item(PrincipalType.USER, Long.MAX_VALUE, "view", true),
                new SpaceAclBatchRevokeResult.Item(PrincipalType.DEPT, 2, "download", false)), 1, 1, true));
        mvc().perform(post("/api/admin/spaces/1/acl/batch-revoke").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "header-id").content("""
                                {"items":[{"principalType":"USER","principalId":9223372036854775807,"permission":"view"},
                                 {"principalType":"DEPT","principalId":2,"permission":"download"}],"requestId":"body-id"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.spaceId").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].principalId").value(Long.MAX_VALUE))
                .andExpect(jsonPath("$.data.items[0].removed").value(true))
                .andExpect(jsonPath("$.data.items[1].removed").value(false))
                .andExpect(jsonPath("$.data.removedCount").value(1))
                .andExpect(jsonPath("$.data.absentCount").value(1))
                .andExpect(jsonPath("$.data.replayed").value(true));
        verify(acls).revokeBatch(1L, items, "header-id");
        verify(acls, never()).revoke(anyLong(), any(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    void revokeBatchRequiresHeaderEvenWhenBodyContainsRequestId() throws Exception {
        var mvc = mvc();
        String body = """
                {"items":[{"principalType":"USER","principalId":2,"permission":"view"}],"requestId":"body"}
                """;
        mvc.perform(post("/api/admin/spaces/1/acl/batch-revoke").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/spaces/1/acl/batch-revoke").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", " ").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(acls);
    }

    @Test
    void revokeBatchRejectsInvalidPathAndMalformedBindingBeforeService() throws Exception {
        var mvc = mvc();
        String body = """
                {"items":[{"principalType":"USER","principalId":2,"permission":"view"}]}
                """;
        for (String id : List.of("0", "-1", "abc", "1.5", "9223372036854775808")) {
            mvc.perform(post("/api/admin/spaces/" + id + "/acl/batch-revoke").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Request-Id", "batch").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/admin/spaces/1/acl/batch-revoke").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "batch").content(body.replace("USER", "UNKNOWN")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(acls);
    }

    @Test
    void revokeBatchRejectsPrincipalIdCoercionBeforeCallingService() throws Exception {
        var mvc = mvc();
        for (String id : List.of("2.9", "2.0", "2e0", "\"2\"", "true", "[]", "{}", "9223372036854775808")) {
            mvc.perform(post("/api/admin/spaces/1/acl/batch-revoke").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Request-Id", "batch").content("{\"items\":[{\"principalType\":\"USER\","
                                    + "\"principalId\":" + id + ",\"permission\":\"view\"}]}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(acls);
    }

    @Test
    void revokeBatchRejectsNumericOrCoercedPrincipalTypesBeforeCallingService() throws Exception {
        var mvc = mvc();
        for (String type : List.of("0", "1", "2", "0.0", "\"0\"", "\"1\"", "\"2\"", "true", "[]", "{}", "\"user\"")) {
            mvc.perform(post("/api/admin/spaces/1/acl/batch-revoke").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Request-Id", "batch").content("{\"items\":[{\"principalType\":" + type
                                    + ",\"principalId\":2,\"permission\":\"view\"}]}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(acls);
    }

    @Test
    void revokeBatchAcceptsEachNamedPrincipalTypeWithExactLongIds() throws Exception {
        var mvc = mvc();
        for (PrincipalType type : PrincipalType.values()) {
            var items = List.of(new SpaceAclBatchRevokeItem(type, Long.MAX_VALUE, "view"));
            when(acls.revokeBatch(1L, items, "batch")).thenReturn(new SpaceAclBatchRevokeResult(1L,
                    List.of(new SpaceAclBatchRevokeResult.Item(type, Long.MAX_VALUE, "view", false)), 0, 1, false));
            mvc.perform(post("/api/admin/spaces/1/acl/batch-revoke").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Request-Id", "batch").content("{\"items\":[{\"principalType\":\"" + type.name()
                                    + "\",\"principalId\":9223372036854775807,\"permission\":\"view\"}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].principalType").value(type.name()))
                    .andExpect(jsonPath("$.data.items[0].principalId").value(Long.MAX_VALUE));
            verify(acls).revokeBatch(1L, items, "batch");
        }
    }

    @Test
    void revokeBatchPreservesServiceErrorStatuses() throws Exception {
        var mvc = mvc();
        for (var error : List.of(IdentityException.badRequest("条目重复；上限为 300"),
                IdentityException.forbidden("无权撤销 manage"), IdentityException.disabled("用户已停用"),
                IdentityException.conflict(IdentityErrorCode.CONFLICT, "请求标识已用于不同操作"))) {
            doThrow(error).when(acls).revokeBatch(eq(1L), any(), eq("batch"));
            mvc.perform(post("/api/admin/spaces/1/acl/batch-revoke").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Request-Id", "batch").content("""
                                    {"items":[{"principalType":"USER","principalId":2,"permission":"view"}]}
                                    """))
                    .andExpect(status().is(error.httpStatus()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Test
    void batchPreservesServiceErrorStatuses() throws Exception {
        var mvc = mvc();
        for (var error : List.of(IdentityException.badRequest("主体重复"), IdentityException.forbidden("无权授予 manage"),
                IdentityException.disabled("用户已停用"), IdentityException.notFound("空间不可访问"),
                IdentityException.conflict(IdentityErrorCode.CONFLICT, "审计冲突"))) {
            doThrow(error).when(acls).grantBatch(eq(1L), any(), any(), eq("batch"));
            mvc.perform(post("/api/admin/spaces/1/acl/batch").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Request-Id", "batch").content("""
                                    {"principals":[{"principalType":"USER","principalId":2}],"permissions":["view"]}
                                    """))
                    .andExpect(status().is(error.httpStatus()));
        }
    }
}
