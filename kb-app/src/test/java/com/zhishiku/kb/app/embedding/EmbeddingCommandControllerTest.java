package com.zhishiku.kb.app.embedding;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.embedding.model.EmbeddingReceipt;
import com.zhishiku.kb.app.embedding.service.EmbeddingAuthorizer;
import com.zhishiku.kb.app.embedding.service.EmbeddingCommandService;
import com.zhishiku.kb.app.embedding.web.EmbeddingCommandController;
import com.zhishiku.kb.common.api.ApiException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmbeddingCommandControllerTest {
    private final EmbeddingCommandService service = mock(EmbeddingCommandService.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final RolePermissionMapper permissions = mock(RolePermissionMapper.class);
    private final EmbeddingAuthorizer authorizer =
            new EmbeddingAuthorizer(new IdentityManagementAuthorizer(permissions), identities);
    private final String start = "{\"profileId\":\"p\",\"expectedEpoch\":0,\"reason\":\"repair\",\"requestId\":\"r\"}";

    @Test
    void authenticatesBothCommandsAndDoesNotGrantWriteWithReadOrReturnReplayAfterRevocation() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new EmbeddingCommandController(authorizer, service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        try (var session = mockStatic(StpUtil.class)) {
            mvc.perform(post("/api/admin/embedding/rebuilds").contentType(MediaType.APPLICATION_JSON).content(start))
                    .andExpect(status().isUnauthorized());
            session.when(StpUtil::isLogin).thenReturn(true);
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            when(permissions.findUserPermissions(7L)).thenReturn(List.of("embedding:read"));
            mvc.perform(post("/api/admin/embedding/rebuilds").contentType(MediaType.APPLICATION_JSON).content(start))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(service);
            when(permissions.findUserPermissions(7L)).thenReturn(List.of("embedding:rebuild"));
            var receipt = new EmbeddingReceipt(UUID.randomUUID(), UUID.randomUUID(), "p", "QUIESCING", 0, 1, false);
            when(service.execute(eq(7L), any())).thenReturn(receipt);
            mvc.perform(post("/api/admin/embedding/rebuilds").contentType(MediaType.APPLICATION_JSON).content(start))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.runId").value(receipt.runId().toString()));
            mvc.perform(post("/api/admin/embedding/rebuilds/" + receipt.runId() + "/resume")
                            .contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "r2")
                            .content("{\"expectedVersion\":0,\"reason\":\"fixed\"}"))
                    .andExpect(status().isAccepted());
            clearInvocations(service);
            when(permissions.findUserPermissions(7L)).thenReturn(List.of());
            mvc.perform(post("/api/admin/embedding/rebuilds").contentType(MediaType.APPLICATION_JSON).content(start))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(service);
        }
    }

    @Test
    void rejectsExtraFieldsBadTypesAndMismatchedIdentifiersWithoutServiceCalls() throws Exception {
        var authorized = mock(EmbeddingAuthorizer.class);
        when(authorized.require(any())).thenReturn(7L);
        var mvc = MockMvcBuilders.standaloneSetup(new EmbeddingCommandController(authorized, service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String body : List.of("null", "[]", "{}", start.replace("0,", "-1,"),
                start.replace("0,", "0.5,"), start.replace("0,", "\"0\","), start.replace("0,", "9223372036854775808,"),
                start.replace("\"repair\"", "\" \""), start.replace("\"r\"", "null"),
                start.replace("\"r\"", "\"" + "r".repeat(129) + "\""),
                start.replace("\"repair\"", "\"" + "x".repeat(501) + "\""),
                start.replace("}", ",\"endpoint\":\"private-url\"}"), start.replace("}", ",\"apiKey\":\"secret\"}"))) {
            mvc.perform(post("/api/admin/embedding/rebuilds").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(content().string(Matchers.not(Matchers.containsString("secret"))));
        }
        mvc.perform(post("/api/admin/embedding/rebuilds").contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "other").content(start)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/embedding/rebuilds/" + UUID.randomUUID() + "/resume")
                .contentType(MediaType.APPLICATION_JSON).content(start)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void mapsConflictAndReadinessFailureTo409And503() throws Exception {
        var authorized = mock(EmbeddingAuthorizer.class);
        when(authorized.require(any())).thenReturn(7L);
        var mvc = MockMvcBuilders.standaloneSetup(new EmbeddingCommandController(authorized, service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(service.execute(eq(7L), any())).thenThrow(ApiException.conflict("conflict"), ApiException.unavailable(
                "not ready"));
        mvc.perform(post("/api/admin/embedding/rebuilds").contentType(MediaType.APPLICATION_JSON).content(start))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/embedding/rebuilds").contentType(MediaType.APPLICATION_JSON).content(start))
                .andExpect(status().isServiceUnavailable());
    }
}
