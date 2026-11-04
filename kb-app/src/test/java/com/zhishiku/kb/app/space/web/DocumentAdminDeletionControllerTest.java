package com.zhishiku.kb.app.space.web;

import com.zhishiku.kb.app.auth.web.IdentityManagementFeedbackAdvice;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.space.model.DocumentAdminDeleteResult;
import com.zhishiku.kb.app.space.service.DocumentAdminDeletionService;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端删除接口的路由与错误投影。
 *
 * <p>核心事实：{@link IdentityException} 自带的状态码真的落到了响应上，并经
 * {@link IdentityManagementFeedbackAdvice} 映射出固定 reasonCode——400 请求自身就错、
 * 404 目标不可达（不存在、已删除、无权同形）、409 归属已变化；以及请求形状错误时
 * 请求压根不到服务层。共用主体的通用异常已在 {@code DocumentAdminDeletionService}
 * 内改写为这里的身份领域异常，故控制器只面对 {@link IdentityException}。
 */
class DocumentAdminDeletionControllerTest {

    private static final String PATH = "/api/admin/documents/10";

    private final DocumentAdminDeletionService deletions = mock(DocumentAdminDeletionService.class);

    private MockMvc mvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        GlobalExceptionHandler fallback = new GlobalExceptionHandler();
        return MockMvcBuilders
                .standaloneSetup(new DocumentAdminDeletionController(deletions))
                .setValidator(validator)
                .setControllerAdvice(fallback, new IdentityManagementFeedbackAdvice(fallback))
                .build();
    }

    @Test
    @DisplayName("删除成功：200，回执只含文档 ID、空间 ID 与 replayed")
    void successfulDeletionReturnsMinimalReceipt() throws Exception {
        when(deletions.delete(10L, "req-1")).thenReturn(new DocumentAdminDeleteResult(10L, 3L, false));

        mvc().perform(delete(PATH).header("X-Request-Id", "req-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(10))
                .andExpect(jsonPath("$.data.spaceId").value(3))
                .andExpect(jsonPath("$.data.replayed").value(false))
                .andExpect(jsonPath("$.data.title").doesNotExist())
                .andExpect(jsonPath("$.data.fileName").doesNotExist())
                .andExpect(content().string(not(containsString("objectKey"))));
    }

    @Test
    @DisplayName("重放回执把 replayed 置位，状态仍是 200")
    void replayedDeletionIsStillOk() throws Exception {
        when(deletions.delete(10L, "req-replay")).thenReturn(new DocumentAdminDeleteResult(10L, 3L, true));

        mvc().perform(delete(PATH).header("X-Request-Id", "req-replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));
    }

    @Test
    @DisplayName("大整数 ID 精确回传，不被截断")
    void largeIdentifiersAreExact() throws Exception {
        long id = 9007199254740993L;
        when(deletions.delete(id, "req-big")).thenReturn(new DocumentAdminDeleteResult(id, id, false));

        mvc().perform(delete("/api/admin/documents/" + id).header("X-Request-Id", "req-big"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(id))
                .andExpect(jsonPath("$.data.spaceId").value(id));
    }

    @Test
    @DisplayName("缺少或空白的 X-Request-Id：400 并投影 INVALID_REQUEST，请求不到服务层")
    void missingRequestIdIsBadRequestAndDoesNotReachService() throws Exception {
        mvc().perform(delete(PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasonCode").value("INVALID_REQUEST"));
        mvc().perform(delete(PATH).header("X-Request-Id", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasonCode").value("INVALID_REQUEST"));

        verify(deletions, never()).delete(anyLong(), anyString());
    }

    @Test
    @DisplayName("路径参数不是正整数：400，请求不到服务层")
    void nonPositiveDocumentIdIsBadRequest() throws Exception {
        for (String bad : List.of("0", "-1", "abc", "1.5")) {
            mvc().perform(delete("/api/admin/documents/" + bad).header("X-Request-Id", "req-1"))
                    .andExpect(status().isBadRequest());
        }

        verify(deletions, never()).delete(anyLong(), anyString());
    }

    @Test
    @DisplayName("不存在、已删除或无权管理：404 投影 NOT_FOUND，固定文案不区分三者也不回显原文")
    void unreachableTargetIsNotFound() throws Exception {
        when(deletions.delete(10L, "req-404"))
                .thenThrow(IdentityException.notFound("文档不存在，或当前用户无权管理其所属空间"));

        mvc().perform(delete(PATH).header("X-Request-Id", "req-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reasonCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("目标不存在或当前不可访问。"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(content().string(not(containsString("无权管理"))));
    }

    @Test
    @DisplayName("共用主体报 404（已被用户端删除）经服务改写后：同样是 404 / NOT_FOUND")
    void sharedBodyNotFoundIsNotFound() throws Exception {
        when(deletions.delete(10L, "req-gone")).thenThrow(IdentityException.notFound("文档不存在，或当前用户无权管理其所属空间"));

        mvc().perform(delete(PATH).header("X-Request-Id", "req-gone"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reasonCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("判定后归属变化：409 投影 CONFLICT，不塌成 400 或 404")
    void ownershipChangeIsConflict() throws Exception {
        when(deletions.delete(10L, "req-409")).thenThrow(
                IdentityException.conflict(IdentityErrorCode.CONFLICT, "文档归属已变化，请重新读取后再决定是否删除"));

        mvc().perform(delete(PATH).header("X-Request-Id", "req-409"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("当前状态已变化，请重新读取后确认。"))
                .andExpect(content().string(not(containsString("归属已变化"))));
    }

    @Test
    @DisplayName("请求体被忽略：DELETE 不读取正文，请求标识只来自请求头")
    void bodyIsIgnoredAndRequestIdComesOnlyFromHeader() throws Exception {
        when(deletions.delete(10L, "hdr-1")).thenReturn(new DocumentAdminDeleteResult(10L, 3L, false));

        mvc().perform(delete(PATH).header("X-Request-Id", "hdr-1")
                        .contentType("application/json").content("{\"requestId\":\"body-1\",\"spaceId\":99}"))
                .andExpect(status().isOk());

        verify(deletions).delete(10L, "hdr-1");
    }
}
