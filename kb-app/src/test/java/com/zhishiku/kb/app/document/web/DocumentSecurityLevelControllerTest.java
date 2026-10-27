package com.zhishiku.kb.app.document.web;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.SecurityLevelDictionary;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.document.mapper.DocumentSecurityLevelMapper;
import com.zhishiku.kb.app.document.model.DocumentSecurityLevelState;
import com.zhishiku.kb.app.document.service.DocumentSecurityLevelService;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyShort;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * standalone MVC 与模拟数据访问，不启动 Spring 应用或外部服务。
 */
class DocumentSecurityLevelControllerTest {
    private static final String URL = "/api/admin/documents/7/security-level";
    private static final String BODY = "{\"securityLevel\":2,\"securityVersion\":0,\"reason\":\"定级复核\"}";
    private final DocumentSecurityLevelMapper mapper = mock(DocumentSecurityLevelMapper.class);
    private final IdentityManagementAuthorizer authorizer = mock(IdentityManagementAuthorizer.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final SecurityLevelDictionary levels = mock(SecurityLevelDictionary.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private MockMvc mvc;
    private MockedStatic<StpUtil> session;

    @BeforeEach
    void setUp() {
        session = mockStatic(StpUtil.class);
        when(authorizer.require(any())).thenReturn(1L);
        when(identities.resolvePrincipal(1)).thenReturn(new ResolvedPrincipal(1, 10, (short) 3, List.of(),
                List.of(10L)));
        when(mapper.find(7)).thenReturn(new DocumentSecurityLevelState(7, (short) 1, 0));
        when(mapper.lockDocument(7)).thenReturn(new DocumentSecurityLevelState(7, (short) 1, 0));
        when(mapper.updateLevel(7, (short) 2, 0)).thenReturn(1);
        when(levels.contains((short) 2)).thenReturn(true);
        var event = new IdentityAuditRecord();
        event.setCreatedAt(Instant.parse("2026-10-05T00:00:00Z"));
        when(audit.successWithReason(any(), anyString(), anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString()))
                .thenReturn(event);
        var service = new DocumentSecurityLevelService(mapper, authorizer, identities, mock(SqlSessionTemplate.class),
                levels, audit, new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new DocumentSecurityLevelController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void close() {
        session.close();
    }

    @Test
    void getReturnsOnlyThreeManagementFields() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(7))
                .andExpect(jsonPath("$.data.securityLevel").value(1))
                .andExpect(jsonPath("$.data.securityVersion").value(0))
                .andExpect(jsonPath("$.data.*", Matchers.hasSize(3)));
    }

    @Test
    void patchReturnsReceiptAndUsesSessionActor() throws Exception {
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "req-1").content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.documentId").value(7))
                .andExpect(jsonPath("$.data.previousSecurityLevel").value(1))
                .andExpect(jsonPath("$.data.securityLevel").value(2))
                .andExpect(jsonPath("$.data.previousSecurityVersion").value(0))
                .andExpect(jsonPath("$.data.securityVersion").value(1))
                .andExpect(jsonPath("$.data.requestId").value("req-1"))
                .andExpect(jsonPath("$.data.replayed").value(false));
        verify(audit).successWithReason(eq(AuditScope.DOCUMENT_SECURITY_LEVEL_CHANGE), eq("req-1"), eq(1L), eq(7L),
                eq("change_security_level"), anyString(), anyString(), eq("定级复核"));
    }

    @Test
    void bodyIdAndMatchingHeaderAreAccepted() throws Exception {
        String body = BODY.replace("}", ",\"requestId\":\" body-id \"}");
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content(body).header("X-Request-Id", "body-id"))
                .andExpect(status().isOk());
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content(body).header("X-Request-Id", "another"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]", "{", "{\"securityLevel\":2,\"reason\":\"原因\"}",
            "{\"securityLevel\":2,\"securityVersion\":-1,\"reason\":\"原因\"}",
            "{\"securityLevel\":2.5,\"securityVersion\":0,\"reason\":\"原因\"}",
            "{\"securityLevel\":\"2\",\"securityVersion\":0,\"reason\":\"原因\"}",
            "{\"securityLevel\":2,\"securityVersion\":0,\"reason\":42}",
            "{\"securityLevel\":32768,\"securityVersion\":0,\"reason\":\"原因\"}",
            "{\"securityLevel\":2,\"securityVersion\":9223372036854775808,\"reason\":\"原因\"}",
            "{\"securityLevel\":2,\"securityVersion\":0,\"reason\":\" \"}"
    })
    void malformedInputIsBadRequest(String body) throws Exception {
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "r").content(body))
                .andExpect(status().isBadRequest());
        verify(mapper, never()).updateLevel(anyLong(), anyShort(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"actorUserId", "clearance", "spaceId", "acl", "title"})
    void unrelatedFieldsAreRejected(String field) throws Exception {
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "r")
                        .content(BODY.replace("}", ",\"" + field + "\":1}")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }

    @Test
    void missingIdIsBadRequest() throws Exception {
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }

    @Test
    void authenticationAndPermissionErrorsKeepTheirHttpStatuses() throws Exception {
        session.when(StpUtil::checkLogin).thenThrow(new SaTokenException("expired"));
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        session.when(StpUtil::checkLogin).thenAnswer(call -> null);
        when(authorizer.require(any())).thenThrow(IdentityException.forbidden("缺少权限"));
        mvc.perform(get(URL)).andExpect(status().isForbidden());
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(mapper);
    }

    @Test
    void missingDeletedAndStaleStateAreMappedCorrectly() throws Exception {
        when(mapper.find(7)).thenReturn(null);
        mvc.perform(get(URL)).andExpect(status().isNotFound());
        when(mapper.lockDocument(7)).thenReturn(null);
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "r").content(BODY))
                .andExpect(status().isNotFound());
        when(mapper.lockDocument(7)).thenReturn(new DocumentSecurityLevelState(7, (short) 1, 9));
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "r").content(BODY))
                .andExpect(status().isConflict());
    }
}
