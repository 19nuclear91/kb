package com.zhishiku.kb.app.search;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.config.PasswordChangeRequiredInterceptor;
import com.zhishiku.kb.app.config.SearchResponseFilter;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.execution.SearchResponseEncoder;
import com.zhishiku.kb.app.search.execution.SearchTransaction;
import com.zhishiku.kb.app.search.mapper.SearchOptionMapper;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.service.SearchOptionReader;
import com.zhishiku.kb.app.search.service.SearchOptionService;
import com.zhishiku.kb.app.search.web.SearchOptionController;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchResponseProtectionTest {
    @Test
    void actualPermissionChainRejectsDisabledCandidateIdentityWithoutLeakingItsFailure() throws Exception {
        String canary = "IDENTITY_QUERY_CURSOR_CANARY";
        var identities = mock(IdentityService.class);
        var failure = IdentityException.disabled(canary);
        failure.initCause(new IllegalStateException(canary));
        when(identities.resolvePrincipal(7)).thenThrow(failure);
        var acl = mock(PermissionMapper.class);
        var candidates = mock(SearchOptionMapper.class);
        var transactions = mock(SearchTransaction.class);
        var mvc = candidateMvc(identities, acl, candidates, transactions);
        var logger = (Logger) LoggerFactory.getLogger("com.zhishiku.kb.app");
        var previousLevel = logger.getLevel();
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(captured);
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (String path : List.of("/api/search/categories", "/api/search/tags")) {
                String body = path.endsWith("categories") ? "{\"spaceId\":1}" : "{\"nameQuery\":\"" + canary + "\"}";
                var response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn().getResponse();
                assertThat(response.getContentAsString()).doesNotContain(canary);
            }
            verify(identities, times(2)).resolvePrincipal(7);
            verifyNoInteractions(acl, candidates, transactions);
            assertThat(captured.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(canary);
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(captured.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("stage=identity", "category=DISABLED", "status=403"));
        } finally {
            logger.detachAppender(captured);
            logger.setLevel(previousLevel);
            captured.stop();
        }
    }

    @Test
    void enabledEmptyDirectoryStaysSuccessfulButNextDisabledReadIsRejected() throws Exception {
        var identities = mock(IdentityService.class);
        when(identities.resolvePrincipal(7)).thenReturn(new ResolvedPrincipal(7, 5, (short) 2, List.of(), List.of()));
        var candidates = mock(SearchOptionMapper.class);
        var transactions = mock(SearchTransaction.class);
        when(transactions.executeSnapshot(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        var mvc = candidateMvc(identities, mock(PermissionMapper.class), candidates, transactions);
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (String path : List.of("/api/search/categories", "/api/search/tags")) {
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                                .content(path.endsWith("categories") ? "{\"spaceId\":1}" : "{}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
            }
            when(identities.resolvePrincipal(7)).thenThrow(IdentityException.disabled("用户已停用"));
            for (String path : List.of("/api/search/categories", "/api/search/tags")) {
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                                .content(path.endsWith("categories") ? "{\"spaceId\":1}" : "{}"))
                        .andExpect(status().isForbidden());
            }
            verify(identities, times(4)).resolvePrincipal(7);
            verify(transactions, times(2)).executeSnapshot(any());
            verify(candidates).tags(null, null, 21, List.of(), List.of("1:7"), (short) 2);
            verify(candidates).categoryContext(1, null, List.of(), List.of("1:7"), (short) 2);
        }
    }

    private static MockMvc candidateMvc(IdentityService identities, PermissionMapper acl,
                                         SearchOptionMapper candidates, SearchTransaction transactions) {
        var permissions = new PermissionService(acl, identities);
        var service = new SearchOptionService(permissions, new SearchOptionReader(candidates), SearchExecutionStub.immediate(),
                transactions, new SearchResponseEncoder(new ObjectMapper(), SearchLimitsProperties.defaults()));
        var password = mock(IdentityDepartmentMapper.class);
        when(password.findMustChangePasswordById(7)).thenReturn(false);
        return MockMvcBuilders.standaloneSetup(new SearchOptionController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new SearchResponseFilter())
                .addInterceptors(new SaInterceptor(handle -> StpUtil.checkLogin()), new PasswordChangeRequiredInterceptor(password)).build();
    }

    @Test
    void allSearchErrorsKeepBodiesAndCauseChainsOutOfResponsesAndLogs() {
        String canary = "QUERY_CURSOR_BODY_CANARY";
        var handler = new GlobalExceptionHandler();
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level level = logger.getLevel();
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(captured);
        try {
            for (String path : List.of("/api/search", "/api/search/categories", "/api/search/tags")) {
                var request = new MockHttpServletRequest("POST", path);
                request.setQueryString("cursor=" + canary);
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                var responses = List.of(
                        handler.handleAuth(new NotLoginException(canary, NotLoginException.NOT_TOKEN, null)),
                        handler.handleIdentity(IdentityException.passwordChangeRequired(canary)),
                        handler.handleInvalidRequest(new IllegalArgumentException(canary)),
                        handler.handleApi(ApiException.conflict(canary)),
                        handler.handleSearch(new SearchFailure(SearchFailure.Kind.USER_LIMIT, 1)),
                        handler.handleSearch(new SearchFailure(SearchFailure.Kind.DEPENDENCY)),
                        handler.handleOther(new IllegalStateException(canary, new RuntimeException(canary))));
                assertThat(responses).extracting(response -> response.getStatusCode().value()).containsExactly(401, 403, 400, 409, 429, 503, 500);
                assertThat(responses).allSatisfy(response -> {
                    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
                    assertThat(response.getBody().toString()).doesNotContain(canary);
                });
            }
            assertThat(captured.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(canary);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            RequestContextHolder.resetRequestAttributes();
            logger.detachAppender(captured);
            logger.setLevel(level);
            captured.stop();
        }
    }

    @Test
    void candidateAuthenticationAndPasswordRejectionsPassThroughEarlyResponseFilter() throws Exception {
        var service = mock(SearchOptionService.class);
        var identity = mock(IdentityDepartmentMapper.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SearchOptionController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new SearchResponseFilter())
                .addInterceptors(new SaInterceptor(handle -> StpUtil.checkLogin()), new PasswordChangeRequiredInterceptor(identity)).build();
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (String path : List.of("/api/search/categories", "/api/search/tags")) {
                session.when(StpUtil::checkLogin).thenThrow(new NotLoginException("CANARY", NotLoginException.NOT_TOKEN, null));
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
                session.when(StpUtil::checkLogin).thenAnswer(call -> null);
                when(identity.findMustChangePasswordById(7)).thenReturn(true);
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"));
            }
        }
        verifyNoInteractions(service);
    }
}
