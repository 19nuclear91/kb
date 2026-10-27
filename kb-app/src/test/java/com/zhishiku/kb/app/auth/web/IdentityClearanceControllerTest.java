package com.zhishiku.kb.app.auth.web;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.DepartmentService;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.IdentitySummary;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 密级调整端点的契约。
 *
 * <p>沿用 {@code IdentityManagementControllerTest} 的 standalone MockMvc + mock service
 * 方式：不启动 Spring 上下文，不连库。
 *
 * <p>这里<b>不</b>重复服务层的判定（那些在 {@code IdentityServiceClearanceTest}）。
 * 控制器只负责三件事，每件都能独立坏掉：资格判定在读写资源之前发生、
 * 操作者取自会话而不是请求体、缺请求标识不进入服务层。
 */
class IdentityClearanceControllerTest {

    private static final short SECRET = 2;
    private static final long ACTOR = 1L;

    private IdentityService identities;
    private IdentityManagementAuthorizer authorizer;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        identities = mock(IdentityService.class);
        authorizer = mock(IdentityManagementAuthorizer.class);
        when(authorizer.require(ArgumentMatchers.any(Permission.class))).thenReturn(ACTOR);
        IdentityManagementController controller = new IdentityManagementController(
                identities, mock(DepartmentService.class), mock(IdentityAuditService.class), authorizer);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("成功：返回新密级与新版本，且操作者取自会话而非请求体")
    void successReturnsNewClearanceAndVersionWithSessionActor() throws Exception {
        when(identities.changeClearance(7L, SECRET, 4L, ACTOR, "req-1"))
                .thenReturn(new IdentitySummary(7L, "bob", "Bob", 2L, SECRET, true, 5L));

        mvc.perform(patch("/api/admin/identity/users/7/clearance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-1")
                        .content("{\"clearanceLevel\":2,\"version\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clearanceLevel").value(2))
                .andExpect(jsonPath("$.data.version").value(5));

        // actor 必须是 require(org.mockito.ArgumentMatchers.any(com.zhishiku.kb.app.auth.model.Permission.class))
        // 返回的会话用户。若控制器改成从请求体取操作者，
        // 服务端那条"不得调整自己的密级"判定就等于不存在——调用方只要声明别人的 ID
        // 就能绕过它，而这个断言是唯一会因此失败的地方。
        verify(identities).changeClearance(7L, SECRET, 4L, ACTOR, "req-1");
    }

    @Test
    @DisplayName("请求体内的 requestId 与请求头等效")
    void requestIdMayComeFromBody() throws Exception {
        when(identities.changeClearance(7L, SECRET, 4L, ACTOR, "body-1"))
                .thenReturn(new IdentitySummary(7L, "bob", "Bob", 2L, SECRET, true, 5L));

        mvc.perform(patch("/api/admin/identity/users/7/clearance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearanceLevel\":2,\"version\":4,\"requestId\":\"body-1\"}"))
                .andExpect(status().isOk());

        verify(identities).changeClearance(7L, SECRET, 4L, ACTOR, "body-1");
    }

    @Test
    @DisplayName("缺请求标识返回 400，且不进入服务层")
    void missingRequestIdIsRejectedWithoutTouchingTheService() throws Exception {
        mvc.perform(patch("/api/admin/identity/users/7/clearance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearanceLevel\":2,\"version\":4}"))
                .andExpect(status().isBadRequest());

        verify(identities, never()).changeClearance(anyLong(), anyShort(), anyLong(), any(), any());
    }

    /**
     * 缺 version 必须被拒，不得按"最新版本"放行。
     *
     * <p>断言的是"没有生效"，而不是具体状态码。缺 {@code version} 由
     * {@code @NotNull} 拦下，而本应用的 {@code GlobalExceptionHandler} 没有
     * {@code MethodArgumentNotValidException} 处理器，于是所有端点的请求体校验失败
     * 都落到兜底分支、返回 500 而不是 400。那是<b>既有行为</b>，全仓库一致，
     * 不是密级端点独有的问题，修它要改全局异常处理、影响每一个端点，超出本变更。
     *
     * <p>这里刻意不写 {@code isBadRequest()}：那会让测试断言一个不存在的行为；
     * 也刻意不写 {@code isInternalServerError()}：那会把一个待修的缺陷钉成契约，
     * 将来真去补 400 时反倒要先改这个测试。
     */
    @Test
    @DisplayName("缺 version 不得生效，不得按\"最新版本\"放行")
    void missingVersionIsRejected() throws Exception {
        mvc.perform(patch("/api/admin/identity/users/7/clearance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-1")
                        .content("{\"clearanceLevel\":2}"))
                .andExpect(status().is(Matchers.greaterThanOrEqualTo(400)));

        verify(identities, never()).changeClearance(anyLong(), anyShort(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("无全局管理资格返回 403，且资格判定先于服务调用")
    void withoutManagerRoleReturnsForbiddenBeforeTouchingTheService() throws Exception {
        doThrow(IdentityException.forbidden("无权执行身份管理操作"))
                .when(authorizer).require(ArgumentMatchers.any(Permission.class));

        mvc.perform(patch("/api/admin/identity/users/7/clearance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-1")
                        .content("{\"clearanceLevel\":2,\"version\":4}"))
                .andExpect(status().isForbidden());

        verify(identities, never()).changeClearance(anyLong(), anyShort(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("以自身为目标返回 403")
    void selfTargetReturnsForbidden() throws Exception {
        when(identities.changeClearance(anyLong(), anyShort(), anyLong(), any(), anyString()))
                .thenThrow(IdentityException.forbidden("不能调整自己的密级"));

        mvc.perform(patch("/api/admin/identity/users/" + ACTOR + "/clearance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-1")
                        .content("{\"clearanceLevel\":2,\"version\":4}"))
                .andExpect(status().isForbidden());

        // 控制器必须把会话用户当作 actor 传下去，服务端才有东西可比。
        verify(identities).changeClearance(ACTOR, SECRET, 4L, ACTOR, "req-1");
    }

    /**
     * 密级必须有自己的路径，不能挂在归属或启停下面。
     *
     * <p>如果哪天有人把密级塞进 {@code /users/{id}/department} 的请求体，这个端点
     * 就成了多余的——而多余的那个会先被删掉，连带删掉自我提权判定和独立审计域。
     */
    @Test
    @DisplayName("密级调整有独立路径，不复用归属或启停端点")
    void clearanceHasItsOwnPath() throws Exception {
        when(identities.changeClearance(7L, SECRET, 4L, ACTOR, "req-1"))
                .thenReturn(new IdentitySummary(7L, "bob", "Bob", 2L, SECRET, true, 5L));

        mvc.perform(patch("/api/admin/identity/users/7/clearance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-1")
                        .content("{\"clearanceLevel\":2,\"version\":4}"))
                .andExpect(status().isOk());

        verify(identities, never()).moveToDepartment(anyLong(), anyLong(), anyLong(), any(), any());
        verify(identities, never()).setEnabled(anyLong(), ArgumentMatchers.anyBoolean(),
                anyLong(), any(), any());
    }

    /**
     * 归属转移请求夹带密级：拒绝，且归属与密级都不变。
     *
     * <p><b>为什么不能静默忽略</b>：Jackson 默认丢弃未知字段，所以这个请求本来会返回
     * 200 并且只改了归属。调用方以为密级也一并设好了——那种"成功但没做"的响应不会进
     * 错误日志、不会触发重试，只会让一次预期中的收紧悄悄没有发生。
     *
     * <p>"归属不变"这条同样要断言：夹带密级的请求若在拒绝前先改了归属，
     * 调用方会收到 400 却留下了副作用，重试时的 version 已经对不上了。
     */
    @Test
    @DisplayName("归属转移夹带密级：返回 400，归属与密级均不变")
    void smugglingClearanceThroughDepartmentMoveIsRejected() throws Exception {
        mvc.perform(patch("/api/admin/identity/users/7/department")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-1")
                        .content("{\"departmentId\":3,\"version\":4,\"clearanceLevel\":3}"))
                .andExpect(status().isBadRequest());

        verify(identities, never()).moveToDepartment(anyLong(), anyLong(), anyLong(), any(), any());
        verify(identities, never()).changeClearance(anyLong(), anyShort(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("用户启停夹带密级：返回 400，状态与密级均不变")
    void smugglingClearanceThroughUserEnableIsRejected() throws Exception {
        mvc.perform(patch("/api/admin/identity/users/7/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-1")
                        .content("{\"enabled\":false,\"version\":4,\"clearanceLevel\":3}"))
                .andExpect(status().isBadRequest());

        verify(identities, never()).setEnabled(anyLong(), ArgumentMatchers.anyBoolean(),
                anyLong(), any(), any());
        verify(identities, never()).changeClearance(anyLong(), anyShort(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("部门启停夹带密级：返回 400，不进入部门服务")
    void smugglingClearanceThroughDepartmentEnableIsRejected() throws Exception {
        mvc.perform(patch("/api/admin/identity/departments/5/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-1")
                        .content("{\"enabled\":false,\"version\":2,\"clearanceLevel\":3}"))
                .andExpect(status().isBadRequest());

        verify(identities, never()).changeClearance(anyLong(), anyShort(), anyLong(), any(), any());
    }

    /**
     * 夹带密级的判定必须在资格判定之后。
     *
     * <p>否则无资格的调用方会因请求体形状拿到 400，而带同样形状的合法调用方拿到 403
     * ——错误码开始回答"你的请求长什么样"而不是"你有没有资格"，
     * 于是请求体形状成了一个无需登录即可探测的信息通道。
     */
    @Test
    @DisplayName("无资格时先返回 403，夹带判定不抢先")
    void missingQualificationWinsOverSmugglingCheck() throws Exception {
        doThrow(IdentityException.forbidden("无权执行身份管理操作"))
                .when(authorizer).require(ArgumentMatchers.any(Permission.class));

        mvc.perform(patch("/api/admin/identity/users/7/department")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-1")
                        .content("{\"departmentId\":3,\"version\":4,\"clearanceLevel\":3}"))
                .andExpect(status().isForbidden());
    }

    /**
     * 不夹带密级的请求不受影响。
     *
     * <p>守卫写成"只要请求体里有这个字段就拒"是容易的，但那样也会拒掉合法请求——
     * 而拒绝合法请求的守卫会被当成 bug 摘掉，连带摘掉真正的防护。所以这里钉住
     * 空值/缺省两种形态都能正常通过。
     */
    @Test
    @DisplayName("不带密级字段的归属转移照常受理")
    void ordinaryMoveWithoutClearanceFieldStillWorks() throws Exception {
        when(identities.moveToDepartment(7L, 3L, 4L, ACTOR, "req-1"))
                .thenReturn(new IdentitySummary(7L, "bob", "Bob", 3L, SECRET, true, 5L));

        mvc.perform(patch("/api/admin/identity/users/7/department")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-1")
                        .content("{\"departmentId\":3,\"version\":4}"))
                .andExpect(status().isOk());

        verify(identities).moveToDepartment(7L, 3L, 4L, ACTOR, "req-1");
    }
}
