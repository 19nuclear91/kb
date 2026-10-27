package com.zhishiku.kb.app.space.web;

import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.space.service.DocumentRelocationService;
import com.zhishiku.kb.common.security.DocumentRelocationResult;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 搬迁接口的错误语义。
 *
 * <p>这里确认的核心事实是：{@link IdentityException} 自带的 {@code httpStatus} 真的落到
 * 了响应状态上。四个状态各有不同含义，塌成同一个就会把"你没资格"和"文档已被别人
 * 搬走"混为一谈：
 * <ul>
 *   <li>400 请求自身就错了（缺幂等标识、源目标相同、夹带密级）</li>
 *   <li>403 缺某一侧空间的 manage 资格</li>
 *   <li>404 文档或目标空间不存在</li>
 *   <li>409 请求没错，但当前状态不允许（归属声明不符、目标已归档）</li>
 * </ul>
 */
class DocumentRelocationControllerTest {

    private static final String BODY = "{\"fromSpaceId\":1,\"toSpaceId\":2}";

    private final DocumentRelocationService relocations = mock(DocumentRelocationService.class);

    private MockMvc mvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders
                .standaloneSetup(new DocumentRelocationController(relocations))
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("搬迁成功：回执带原归属、新归属、清除条数与密级受阻数")
    void successfulRelocationReturnsTheReceipt() throws Exception {
        when(relocations.relocate(eq(10L), eq(1L), eq(2L), eq(null), any(), eq("hdr-1")))
                .thenReturn(new DocumentRelocationResult(10L, 1L, 2L, 2,
                        List.of("3:5:view", "1:8:download"), false, 1L, false));

        mvc().perform(patch("/api/admin/documents/10/space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "hdr-1")
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fromSpaceId").value(1))
                .andExpect(jsonPath("$.data.toSpaceId").value(2))
                .andExpect(jsonPath("$.data.clearedExceptionCount").value(2))
                .andExpect(jsonPath("$.data.clearedExceptions[0]").value("3:5:view"))
                .andExpect(jsonPath("$.data.clearedExceptionsTruncated").value(false))
                .andExpect(jsonPath("$.data.clearanceBlockedPrincipalCount").value(1))
                .andExpect(jsonPath("$.data.replayed").value(false));
    }

    @Test
    @DisplayName("请求标识可以只来自请求头")
    void requestIdMayComeFromHeader() throws Exception {
        when(relocations.relocate(eq(10L), eq(1L), eq(2L), eq(null), any(), eq("hdr-9")))
                .thenReturn(new DocumentRelocationResult(10L, 1L, 2L, 0, List.of(), false, 0L, false));

        mvc().perform(patch("/api/admin/documents/10/space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "hdr-9")
                        .content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("请求体里的 requestId 优先于请求头")
    void bodyRequestIdWinsOverHeader() throws Exception {
        when(relocations.relocate(eq(10L), eq(1L), eq(2L), eq(null), any(), eq("body-1")))
                .thenReturn(new DocumentRelocationResult(10L, 1L, 2L, 0, List.of(), false, 0L, false));

        mvc().perform(patch("/api/admin/documents/10/space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "hdr-1")
                        .content("{\"fromSpaceId\":1,\"toSpaceId\":2,\"requestId\":\"body-1\"}"))
                .andExpect(status().isOk());

        verify(relocations).relocate(10L, 1L, 2L, null, null, "body-1");
    }

    @Test
    @DisplayName("两处都没有请求标识：400，且请求压根不到服务层")
    void missingRequestIdIsBadRequestAndDoesNotReachService() throws Exception {
        mvc().perform(patch("/api/admin/documents/10/space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());

        verify(relocations, never()).relocate(anyLong(), anyLong(), anyLong(), any(), any(), anyString());
    }

    @Test
    @DisplayName("缺少某一侧 manage 资格：403，错误消息说明搬迁需要管理资格")
    void missingManageOnEitherSideIsForbidden() throws Exception {
        when(relocations.relocate(eq(10L), eq(1L), eq(2L), eq(null), any(), eq("req-403")))
                .thenThrow(IdentityException.forbidden(
                        "搬迁文档需要目标空间（2）的 manage 管理资格；edit 不足以把文档推送给另一个空间的全部受众"));

        mvc().perform(patch("/api/admin/documents/10/space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-403")
                        .content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("管理资格")));
    }

    @Test
    @DisplayName("目标空间不存在：404")
    void missingTargetSpaceIsNotFound() throws Exception {
        when(relocations.relocate(eq(10L), eq(1L), eq(2L), eq(null), any(), eq("req-404")))
                .thenThrow(IdentityException.notFound("目标空间不存在或已删除"));

        mvc().perform(patch("/api/admin/documents/10/space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-404")
                        .content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("声明的当前所属空间与实际不符：409，不塌成 400 或 403")
    void declaredSourceMismatchIsConflict() throws Exception {
        when(relocations.relocate(eq(10L), eq(1L), eq(2L), eq(null), any(), eq("req-409")))
                .thenThrow(IdentityException.conflict(IdentityErrorCode.CONFLICT,
                        "文档当前不在声明的源空间 1 内，可能已被他人搬走；请重新读取归属后再决定是否搬迁"));

        mvc().perform(patch("/api/admin/documents/10/space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-409")
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("可能已被他人搬走")));
    }

    @Test
    @DisplayName("请求夹带密级：400，并说明密级调整是独立操作")
    void requestCarryingSecurityLevelIsBadRequest() throws Exception {
        when(relocations.relocate(eq(10L), eq(1L), eq(2L), eq((short) 3), any(), eq("req-400")))
                .thenThrow(IdentityException.badRequest(
                        "搬迁不修改文档密级；密级调整是独立的、受全局管理员保护的操作，不能夹带在搬迁请求里"));

        mvc().perform(patch("/api/admin/documents/10/space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-400")
                        .content("{\"fromSpaceId\":1,\"toSpaceId\":2,\"securityLevel\":3}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 非正数空间 ID 被 bean validation 拦在服务层之前。
     *
     * <p>这里<b>不</b>断言状态码：{@code GlobalExceptionHandler} 目前没有
     * {@code MethodArgumentNotValidException} 的处理分支，所以请求体校验失败会落到
     * 兜底分支返回 500 而不是 400。这是既有缺口，文档例外授权接口（同样带
     * {@code @Valid} 的请求体）也一样，不是本变更引入的，修它会改变所有接口的行为，
     * 因此不在本变更范围内。本测试守住真正重要的那一半：请求没有到达服务层。
     */
    @Test
    @DisplayName("非正数的空间 ID 被校验拦下，不进服务层")
    void nonPositiveSpaceIdDoesNotReachTheService() throws Exception {
        mvc().perform(patch("/api/admin/documents/10/space")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "req-1")
                .content("{\"fromSpaceId\":0,\"toSpaceId\":2}"));

        verify(relocations, never()).relocate(anyLong(), anyLong(), anyLong(), any(), any(), anyString());
    }

    @Test
    @DisplayName("重放回执把 replayed 置位，状态仍是 200")
    void replayedRelocationIsStillOk() throws Exception {
        when(relocations.relocate(eq(10L), eq(1L), eq(2L), eq(null), any(), eq("req-replay")))
                .thenReturn(new DocumentRelocationResult(10L, 1L, 2L, 2,
                        List.of("3:5:view"), false, 0L, true));

        mvc().perform(patch("/api/admin/documents/10/space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-replay")
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));
    }
}
