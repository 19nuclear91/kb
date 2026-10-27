package com.zhishiku.kb.app.document;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.config.DocumentReviewResponseFilter;
import com.zhishiku.kb.app.document.service.DocumentReviewQueryService;
import com.zhishiku.kb.app.document.service.DocumentReviewService;
import com.zhishiku.kb.app.document.web.DocumentReviewAdvice;
import com.zhishiku.kb.app.document.web.DocumentReviewController;
import com.zhishiku.kb.common.review.ReviewError;
import com.zhishiku.kb.common.review.ReviewFailure;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentReviewControllerTest {
    private final DocumentReviewQueryService queries = mock(DocumentReviewQueryService.class);
    private final DocumentReviewService commands = mock(DocumentReviewService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DocumentReviewController(queries, commands))
            .setControllerAdvice(new DocumentReviewAdvice()).addFilters(new DocumentReviewResponseFilter()).build();
    private static final String PATH = "/api/documents/10/versions/20/reviews";

    @Test
    void strictCommandsRejectUnknownDuplicateFieldsInvalidIdsAndBodyShapes() throws Exception {
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (String body : new String[]{"{}", "[]", "{", "{\"expectedStateVersion\":0,\"actor\":7}",
                    "{\"expectedStateVersion\":0,\"expectedStateVersion\":1}", "{\"expectedStateVersion\":-1}",
                    "{\"expectedStateVersion\":1.5}", "{\"expectedStateVersion\":null}"}) {
                mvc.perform(post(PATH).contentType("application/json").header("X-Request-Id", "req").content(body))
                        .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
            }
            mvc.perform(post(PATH).contentType("application/json").content("{\"expectedStateVersion\":0}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post(PATH).contentType("application/json").header("X-Request-Id", "a", "b")
                            .content("{\"expectedStateVersion\":0}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/documents/0/versions/20/review")).andExpect(status().isBadRequest());
            mvc.perform(post(PATH + "/1-2-3-4-5/withdraw").contentType("application/json").header("X-Request-Id", "r")
                            .content("{\"expectedStateVersion\":0}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post(PATH).param("unknown", "x").contentType("application/json").header("X-Request-Id", "r")
                            .content("{\"expectedStateVersion\":0}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(commands, queries);
        }
    }

    @Test
    void authenticationResourceAndDependencyFailuresStayDistinctAndNoStore() throws Exception {
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenThrow(new SaTokenException("private"));
            mvc.perform(get("/api/reviews").param("kind", "mine")).andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"));
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            doThrow(IdentityException.passwordChangeRequired("private")).when(queries).list(anyLong(), any());
            mvc.perform(get("/api/reviews").param("kind", "mine")).andExpect(status().isForbidden());
            doThrow(new ReviewFailure(ReviewError.NOT_FOUND)).when(queries).status(anyLong(), anyLong(), anyLong(), any());
            mvc.perform(get("/api/documents/10/versions/20/review")).andExpect(status().isNotFound());
            doThrow(new IllegalStateException("private SQL reason")).when(queries).list(anyLong(), any());
            mvc.perform(get("/api/reviews").param("kind", "mine")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.data.errorCode").value("SERVICE_UNAVAILABLE"));
        }
    }

    @Test
    void exactLargeIdsAndExpectedVersionReachServiceWithoutRounding() throws Exception {
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            mvc.perform(post("/api/documents/9007199254740993/versions/9007199254740994/reviews")
                            .contentType("application/json").header("X-Request-Id", "req")
                            .content("{\"expectedStateVersion\":\"9007199254740995\"}"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
            verify(commands).submit(7, 9007199254740993L, 9007199254740994L, 9007199254740995L, "req");
        }
    }
}
