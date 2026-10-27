package com.zhishiku.kb.app.space.web;

import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.space.service.DocumentAclService;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PrincipalType;
import com.zhishiku.kb.common.security.SpaceAclGrantResult;
import com.zhishiku.kb.common.security.SpaceAclSummary;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文档例外接口的错误语义。
 *
 * <p>路径里的 {@code documentId} 只标识文档，接口不接受调用方传入空间 ID：
 * 归属空间由服务层从文档本身查出，因此"越权管理别人的文档"必然表现成 403，
 * 而不是 404——文档是存在的，操作者只是没有管理它的资格。
 */
class DocumentAclControllerTest {

    private final DocumentAclService acls = mock(DocumentAclService.class);

    private MockMvc mvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders
                .standaloneSetup(new DocumentAclController(acls))
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void grantingForDocumentInAnotherSpaceReturnsForbidden() throws Exception {
        when(acls.grant(eq(20L), any(), eq(5L), eq("view"), any(), eq("req-1")))
                .thenThrow(IdentityException.forbidden("无权管理该空间"));

        mvc().perform(post("/api/admin/documents/20/acl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-1")
                        .content("{\"principalType\":\"DEPT\",\"principalId\":5,\"permission\":\"view\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingDocumentReturnsNotFound() throws Exception {
        when(acls.list(99L)).thenThrow(IdentityException.notFound("文档不存在或已删除"));

        mvc().perform(get("/api/admin/documents/99/acl"))
                .andExpect(status().isNotFound());
    }

    @Test
    void grantingManageAtDocumentLevelReturnsBadRequest() throws Exception {
        when(acls.grant(eq(10L), any(), eq(5L), eq("manage"), any(), eq("req-2")))
                .thenThrow(IdentityException.badRequest(
                        "文档例外只能扩权，manage 不能用在文档级例外上；请改用 view、download 或 edit"));

        mvc().perform(post("/api/admin/documents/10/acl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-2")
                        .content("{\"principalType\":\"DEPT\",\"principalId\":5,\"permission\":\"manage\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingRequestIdIsBadRequestAndDoesNotReachService() throws Exception {
        mvc().perform(post("/api/admin/documents/10/acl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"principalType\":\"DEPT\",\"principalId\":5,\"permission\":\"view\"}"))
                .andExpect(status().isBadRequest());

        verify(acls, never()).grant(anyLong(), any(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    void requestIdMayComeFromHeader() throws Exception {
        when(acls.grant(eq(10L), eq(PrincipalType.DEPT), eq(5L), eq("view"), any(), eq("hdr-9")))
                .thenReturn(new SpaceAclGrantResult(
                        new SpaceAclSummary(3L, 10L, PrincipalType.DEPT, 5L, "3:5", "市场部", "view"),
                        false, 1L));

        mvc().perform(post("/api/admin/documents/10/acl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "hdr-9")
                        .content("{\"principalType\":\"DEPT\",\"principalId\":5,\"permission\":\"view\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyGranted").value(false))
                .andExpect(jsonPath("$.data.clearanceBlockedPrincipalCount").value(1))
                .andExpect(jsonPath("$.data.acl.principalKey").value("3:5"));
    }

    @Test
    void revokingMissingExceptionIsStillOk() throws Exception {
        mvc().perform(delete("/api/admin/documents/10/acl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-3")
                        .content("{\"principalType\":\"DEPT\",\"principalId\":5,\"permission\":\"view\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void revokingWithoutManageReturnsForbidden() throws Exception {
        doThrow(IdentityException.forbidden("无权管理该空间"))
                .when(acls).revoke(eq(20L), any(), eq(5L), eq("view"), any(), eq("req-4"));

        mvc().perform(delete("/api/admin/documents/20/acl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-4")
                        .content("{\"principalType\":\"DEPT\",\"principalId\":5,\"permission\":\"view\"}"))
                .andExpect(status().isForbidden());
    }
}
