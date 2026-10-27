package com.zhishiku.kb.app.embedding;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingProfileMapper;
import com.zhishiku.kb.app.embedding.model.EmbeddingViews;
import com.zhishiku.kb.app.embedding.service.EmbeddingAdminReadService;
import com.zhishiku.kb.app.embedding.service.EmbeddingAuthorizer;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.embedding.service.EmbeddingReadinessService;
import com.zhishiku.kb.app.embedding.web.EmbeddingAdminController;
import com.zhishiku.kb.app.search.embedding.EmbeddingInstanceReadiness;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmbeddingAdminReadTest {
    @Test
    void allReadEndpointsRequireLoginActiveIdentityAndReadGrant() throws Exception {
        var identities = mock(IdentityService.class);
        var permissions = mock(RolePermissionMapper.class);
        var service = mock(EmbeddingAdminReadService.class);
        var authorizer = new EmbeddingAuthorizer(new IdentityManagementAuthorizer(permissions), identities);
        var mvc = MockMvcBuilders.standaloneSetup(new EmbeddingAdminController(service, authorizer))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UUID id = UUID.randomUUID();
        List<String> paths = List.of("/profiles", "/status", "/rebuilds/" + id);
        try (var session = mockStatic(StpUtil.class)) {
            for (String path : paths)
                mvc.perform(get("/api/admin/embedding" + path)).andExpect(status().isUnauthorized());
            verifyNoInteractions(service, identities, permissions);
            session.when(StpUtil::isLogin).thenReturn(true);
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            when(permissions.findUserPermissions(7L)).thenReturn(List.of("embedding:rebuild"));
            for (String path : paths) mvc.perform(get("/api/admin/embedding" + path)).andExpect(status().isForbidden());
            verifyNoInteractions(service);
            when(permissions.findUserPermissions(7L)).thenReturn(List.of("embedding:read"));
            when(identities.resolvePrincipal(7L)).thenThrow(IdentityException.disabled("disabled principal"));
            for (String path : paths) mvc.perform(get("/api/admin/embedding" + path)).andExpect(status().isForbidden());
            verifyNoInteractions(service);
            doReturn(null).when(identities).resolvePrincipal(7L);
            var local = new EmbeddingInstanceReadiness.Snapshot(UUID.randomUUID(), "next", "fp", 1L, false,
                    "MISSING_PROFILE");
            when(service.status()).thenReturn(new EmbeddingViews.Status("ACTIVE", "next", null, 1, null, null, local));
            for (String path : paths) mvc.perform(get("/api/admin/embedding" + path)).andExpect(status().isOk());
            mvc.perform(get("/api/admin/embedding/status")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.localEmbedding.instanceId").value(local.instanceId().toString()))
                    .andExpect(jsonPath("$.data.localEmbedding.ready").value(false))
                    .andExpect(jsonPath("$.data.localEmbedding.reason").value("MISSING_PROFILE"));
            when(service.run(id)).thenThrow(ApiException.notFound("embedding run not found"));
            mvc.perform(get("/api/admin/embedding/rebuilds/" + id)).andExpect(status().isNotFound());
        }
    }

    @Test
    void serviceReturnsOnlyWhitelistedFieldsAndSanitizesErrorCodes() throws Exception {
        var profiles = mock(EmbeddingProfileMapper.class);
        var control = mock(EmbeddingControlService.class);
        var readiness = mock(EmbeddingReadinessService.class);
        var profile = new EmbeddingProfile("p", "remote", "source", "model", "revision", 1024, "cosine",
                "identity-v1", "identity-v1", "query", "document", "embeddings-v1");
        var client = mock(ProfileEmbeddingClient.class);
        when(client.profile()).thenReturn(profile);
        when(client.role()).thenReturn(EmbeddingRole.QUERY);
        var clients = new EmbeddingClientRegistry(List.of(new EmbeddingProfileDefinition(profile,
                new EmbeddingConnection("https://private.invalid", "private-key", 30, 16))), EmbeddingRole.QUERY,
                d -> client);
        var instance = new EmbeddingInstanceReadiness(clients);
        var service = new EmbeddingAdminReadService(profiles, control, readiness, instance);
        when(profiles.list()).thenReturn(List.of(profile));
        when(readiness.ready(profile)).thenReturn(true);
        assertThat(service.profiles().getFirst().ready()).isTrue();
        UUID id = UUID.randomUUID();
        when(control.run(id)).thenReturn(new EmbeddingRun(id, "p", 7, "BLOCKED", 4, "REBUILDING", 8,
                UUID.randomUUID(), null, 2, 3, null, 10, 2, 1, "private-response-body"));
        String json = new ObjectMapper().writeValueAsString(service.run(id));
        assertThat(json).contains("INTERNAL_ERROR", "succeededCount").doesNotContain("private-response-body",
                "leaseOwner", "claimToken");
        String profileJson = new ObjectMapper().writeValueAsString(service.profiles());
        assertThat(profileJson).contains("fingerprint").doesNotContain("apiKey", "endpoint", "connection",
                "credential");
        when(control.current()).thenReturn(new EmbeddingControl("UNINITIALIZED", null, null, null, null, 0, null));
        assertThat(service.status().waitingReason()).isEqualTo("EMBEDDING_MAINTENANCE");
        when(control.current()).thenReturn(new EmbeddingControl("ACTIVE", "next", "next-fingerprint", null, null, 1,
                null));
        assertThat(service.profiles().getFirst().ready()).isTrue();
        var local = service.status().localEmbedding();
        assertThat(local.ready()).isFalse();
        assertThat(local.reason()).isEqualTo("MISSING_PROFILE");
        assertThat(local.instanceId()).isEqualTo(instance.instanceId());
        assertThat(new ObjectMapper().writeValueAsString(service.status())).contains("localEmbedding",
                        "MISSING_PROFILE")
                .doesNotContain("private-key", "private.invalid", "endpoint");
        when(control.current()).thenThrow(new IllegalStateException("private database error"));
        assertThatThrownBy(service::status).isInstanceOf(IllegalStateException.class);
        assertThat(instance.snapshot().reason()).isEqualTo("CONTROL_UNAVAILABLE");
        assertThatThrownBy(() -> service.run(UUID.randomUUID())).isInstanceOf(ApiException.class);
    }
}
