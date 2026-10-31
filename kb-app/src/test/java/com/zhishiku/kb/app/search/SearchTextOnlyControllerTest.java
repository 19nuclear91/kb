package com.zhishiku.kb.app.search;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.config.PasswordChangeRequiredInterceptor;
import com.zhishiku.kb.app.search.service.SearchService;
import com.zhishiku.kb.app.search.web.SearchController;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchTextOnlyControllerTest {
    private static final String PATH = "/api/search";

    @Test
    void postUsesBodyDefaultsAndServerIdentity() throws Exception {
        SearchService service = mock(SearchService.class);
        MockMvc mvc = mvc(service);
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"text\"}"))
                    .andExpect(status().isOk());
            verify(service).searchResponse(7L, "text", null, 50, 50);
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"other\",\"candidates\":80,\"topK\":8}"))
                    .andExpect(status().isOk());
            verify(service).searchResponse(7L, "other", null, 80, 8);
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"third\",\"candidates\":80}"))
                    .andExpect(status().isOk());
            verify(service).searchResponse(7L, "third", null, 80, 80);
        }
    }

    @Test
    void invalidBodyAndUrlParametersAreRejectedWithoutServiceCall() throws Exception {
        SearchService service = mock(SearchService.class);
        MockMvc mvc = mvc(service);
        for (String json : List.of("{\"query\":\"  \"}",
                "{\"query\":\"x\",\"candidates\":0}",
                "{\"query\":\"x\",\"candidates\":201}",
                "{\"query\":\"x\",\"topK\":0}",
                "{\"query\":\"x\",\"topK\":201}")) {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest());
        }
        String canary = "QUERY_CANARY_938";
        var response = mvc.perform(post(PATH + "?q=" + canary)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"safe\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(canary);
        mvc.perform(post(PATH + "?tracking=" + canary)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"safe\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void bodyCannotForgeAuthorizationOrVectorInputs() throws Exception {
        SearchService service = mock(SearchService.class);
        MockMvc mvc = mvc(service);
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("""
                    {"query":"text","userId":99,"roleIds":[1],"roles":["admin"],
                     "clearanceLevel":9,"clearance":9,"queryVector":"[1,0]",
                     "profileId":"forged","profile":"forged"}
                    """)).andExpect(status().isOk());
            verify(service).searchResponse(7L, "text", null, 50, 50);
        }
    }

    @Test
    void getIs405AndExistingAuthenticationAndPasswordGatesApplyToPost() throws Exception {
        SearchService service = mock(SearchService.class);
        IdentityDepartmentMapper mapper = mock(IdentityDepartmentMapper.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SearchController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new SaInterceptor(handle -> StpUtil.checkLogin()),
                        new PasswordChangeRequiredInterceptor(mapper)).build();
        String canary = "QUERY_CANARY_938";
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            var getResponse = mvc.perform(get(PATH + "?q=" + canary))
                    .andExpect(status().isMethodNotAllowed()).andReturn().getResponse();
            assertThat(getResponse.getContentAsString()).doesNotContain(canary);

            session.when(StpUtil::checkLogin).thenThrow(new NotLoginException(
                    NotLoginException.NOT_TOKEN_MESSAGE, NotLoginException.NOT_TOKEN, null));
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"x\"}"))
                    .andExpect(status().isUnauthorized());
            session.when(StpUtil::checkLogin).thenAnswer(invocation -> null);
            when(mapper.findMustChangePasswordById(7L)).thenReturn(true);
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"x\"}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void searchExceptionsDoNotExposeMessagesOrCauseChainsInLogsOrResponses() throws Exception {
        String canary = "QUERY_CANARY_938";
        SearchService service = mock(SearchService.class);
        MockMvc mvc = mvc(service);
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            doThrow(
                    new IllegalStateException("message=" + canary,
                            new RuntimeException("cause=" + canary)))
                    .when(service).searchResponse(7L, "x", null, 50, 50);
            assertThat(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"x\"}"))
                    .andExpect(status().isInternalServerError()).andReturn().getResponse()
                    .getContentAsString()).doesNotContain(canary);

            doThrow(IdentityException.forbidden(canary))
                    .when(service).searchResponse(7L, "x", null, 50, 50);
            assertThat(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"x\"}"))
                    .andExpect(status().isForbidden()).andReturn().getResponse()
                    .getContentAsString()).doesNotContain(canary);

            session.when(StpUtil::getLoginIdAsLong).thenThrow(new NotLoginException(
                    canary, NotLoginException.NOT_TOKEN, null));
            assertThat(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"x\"}"))
                    .andExpect(status().isUnauthorized()).andReturn().getResponse()
                    .getContentAsString()).doesNotContain(canary);

            assertThat(captured.list).hasSize(3).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(canary);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(captured);
            captured.stop();
            logger.setLevel(previousLevel);
        }
    }

    private static MockMvc mvc(SearchService service) {
        return MockMvcBuilders.standaloneSetup(new SearchController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
