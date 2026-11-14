package com.zhishiku.kb.app.knowledgegap;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.config.KnowledgeGapResponseFilter;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAccess;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAssignmentService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAssigneeQueryService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapClosureService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapCreateService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapQueryService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapSolutionQueryService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapSolutionService;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapAdvice;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapAssignmentController;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapAssigneeController;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapClosureController;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapController;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapProtocolController;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapQueryController;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapSolutionController;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 全部实际控制器注册到内存 DispatcherServlet，服务与身份为替身，不启动应用或 HTTP 服务。 */
class KnowledgeGapControllerTest {
    private final KnowledgeGapMemoryFixture memory = new KnowledgeGapMemoryFixture();
    private final KnowledgeGapAccess access = mock(KnowledgeGapAccess.class);
    private final KnowledgeGapCreateService creates = mock(KnowledgeGapCreateService.class);
    private final KnowledgeGapAssignmentService assignments = mock(KnowledgeGapAssignmentService.class);
    private final KnowledgeGapClosureService closures = mock(KnowledgeGapClosureService.class);
    private final KnowledgeGapSolutionService solutions = mock(KnowledgeGapSolutionService.class);
    private final KnowledgeGapSolutionQueryService documents = mock(KnowledgeGapSolutionQueryService.class);
    private final KnowledgeGapAssigneeQueryService assignees = mock(KnowledgeGapAssigneeQueryService.class);
    private final KnowledgeGapQueryService queries = mock(KnowledgeGapQueryService.class);
    private final UUID solutionId = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var actor = memory.access.current();
        when(access.current()).thenReturn(actor);
        when(access.requireCurrent(any())).thenReturn(actor);
        when(access.requireForAction(any(), anyLong(), any())).thenReturn(memory.gap);
        when(creates.create(any(), anyString())).thenReturn(receipt(KnowledgeGapState.SUBMITTED, null));
        when(assignments.assign(anyLong(), any(), anyString())).thenReturn(receipt(KnowledgeGapState.IN_PROGRESS, null));
        when(assignments.note(anyLong(), any(), anyString())).thenReturn(receipt(KnowledgeGapState.IN_PROGRESS, null));
        when(assignments.returnToReception(anyLong(), any(), anyString())).thenReturn(receipt(KnowledgeGapState.SUBMITTED, null));
        when(solutions.submit(anyLong(), any(), anyString())).thenReturn(receipt(KnowledgeGapState.AWAITING_CONFIRMATION, solutionId));
        when(solutions.confirm(anyLong(), any(), anyString())).thenReturn(receipt(KnowledgeGapState.RESOLVED, solutionId));
        when(solutions.reject(anyLong(), any(), anyString())).thenReturn(receipt(KnowledgeGapState.IN_PROGRESS, solutionId));
        when(closures.withdraw(anyLong(), any(), anyString())).thenReturn(receipt(KnowledgeGapState.WITHDRAWN, null));
        when(closures.close(anyLong(), any(), anyString())).thenReturn(receipt(KnowledgeGapState.CLOSED, null));
        when(documents.candidates(any(), anyLong(), any())).thenReturn(new QueryPage<>(List.of(), false, null));
        when(assignees.search(any(), anyLong(), any())).thenReturn(new QueryPage<>(List.of(), false, null));
        when(queries.list(any())).thenReturn(new QueryPage<>(List.of(), false, null));
        when(queries.queue(any())).thenReturn(new QueryPage<>(List.of(), false, null));
        when(queries.history(anyString(), any())).thenReturn(new QueryPage<>(List.of(), false, null));
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeGapController(access, creates),
                        new KnowledgeGapAssignmentController(access, assignments), new KnowledgeGapClosureController(access, closures),
                        new KnowledgeGapSolutionController(access, solutions, documents), new KnowledgeGapAssigneeController(access, assignees),
                        new KnowledgeGapQueryController(queries), new KnowledgeGapProtocolController(access))
                .setControllerAdvice(new KnowledgeGapAdvice(), new GlobalExceptionHandler())
                .addFilters(new KnowledgeGapResponseFilter()).build();
    }

    @Test
    void allNineWritePathsUseStrictBodiesAndNoStoreReceipts() throws Exception {
        String[] paths = {"/api/knowledge-gaps", "/api/admin/knowledge-gaps/11/assign", "/api/knowledge-gaps/11/notes",
                "/api/knowledge-gaps/11/return", "/api/knowledge-gaps/11/solutions", "/api/knowledge-gaps/11/confirm",
                "/api/knowledge-gaps/11/reject-solution", "/api/knowledge-gaps/11/withdraw", "/api/admin/knowledge-gaps/11/close"};
        String[] bodies = {creation(), "{\"expectedVersion\":0,\"assigneeUserId\":8}", "{\"expectedVersion\":1,\"text\":\"说明\"}",
                reason(), "{\"expectedVersion\":1,\"text\":\"方案\"}", "{\"expectedVersion\":2,\"solutionId\":\"" + solutionId + "\"}",
                "{\"expectedVersion\":2,\"solutionId\":\"" + solutionId + "\",\"reason\":\"仍未解决\"}", reason(), reason()};
        for (int index = 0; index < paths.length; index++) {
            mvc.perform(post(paths[index]).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "req-" + index).content(bodies[index]))
                    .andExpect(status().is(index == 0 ? 201 : 200)).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.gapId").value(11))
                    .andExpect(jsonPath("$.data.description").doesNotExist()).andExpect(jsonPath("$.data.documentId").doesNotExist());
        }
        var first = receipt(KnowledgeGapState.SUBMITTED, null);
        doReturn(new KnowledgeGapRows.Receipt(first.gapId(), first.resultVersion(), first.state(), first.eventId(), null, true))
                .when(creates).create(any(), anyString());
        mvc.perform(post("/api/knowledge-gaps").contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "req-0").content(creation()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));
    }

    @Test
    void readonlyCandidatePostsRequireNoCommandHeaderAndReadsKeepPageShape() throws Exception {
        for (String path : List.of("/api/knowledge-gaps/11/solution-documents", "/api/admin/knowledge-gaps/11/assignees/search")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data.hasMore").value(false)).andExpect(jsonPath("$.data.items").isArray());
        }
        for (String path : List.of("/api/knowledge-gaps", "/api/admin/knowledge-gaps", "/api/knowledge-gaps/11/history")) {
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data.hasMore").value(false));
        }
        verifyNoInteractions(creates, assignments, closures, solutions);
    }

    @Test
    void unsupportedEditDeleteReopenMediaTypeAndQueryNeverDispatchBusinessWrites() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(delete("/api/knowledge-gaps/11"), put("/api/knowledge-gaps/11"),
                patch("/api/knowledge-gaps/11/notes"), post("/api/knowledge-gaps/11/reopen"),
                post("/api/knowledge-gaps").contentType(MediaType.TEXT_PLAIN).content("secret-body"),
                post("/api/knowledge-gaps").contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "req")
                        .queryParam("description", "secret-query").content(creation()))) {
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.message").value("知识问题单请求无效"));
        }
        verifyNoInteractions(creates, assignments, closures, solutions);
    }

    @Test
    void errorsAreFixedForEveryContractStatusWithoutRawBodyOrSql() throws Exception {
        var errors = List.of(new QueryException(400, "secret-input"), IdentityException.unauthorized("secret-session"),
                ApiException.forbidden("secret-role"), ApiException.notFound("secret-target"), ApiException.conflict("secret-solution"),
                new QueryException(413, "secret-body"), new IllegalStateException("secret-sql"));
        var codes = List.of(400, 401, 403, 404, 409, 413, 503);
        for (int index = 0; index < errors.size(); index++) {
            doThrow(errors.get(index)).when(queries).detail(anyString(), any());
            String response = mvc.perform(get("/api/knowledge-gaps/11")).andExpect(status().is(codes.get(index)))
                    .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data").isEmpty())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("secret");
        }
    }

    @Test
    void missingDuplicateCommandHeadersAndOversizedBodyAreRejectedBeforeWrite() throws Exception {
        mvc.perform(post("/api/knowledge-gaps").contentType(MediaType.APPLICATION_JSON).content(creation())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/knowledge-gaps").contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "one", "two").content(creation()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/knowledge-gaps").contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "one")
                        .content(" ".repeat(65537)))
                .andExpect(status().isPayloadTooLarge()).andExpect(header().string("Cache-Control", "no-store"));
        doThrow(IdentityException.unauthorized("secret-session")).when(access).current();
        mvc.perform(post("/api/knowledge-gaps").contentType(MediaType.APPLICATION_JSON).content("broken secret-body"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(creates);
    }

    @Test
    void frameworkFallbackDoesNotLogOrEchoRawFailuresAndOtherApisAreUnchanged() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        var global = new GlobalExceptionHandler();
        try {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST", "/api/knowledge-gaps")));
            assertThat(global.handleOther(new IllegalStateException("secret-sql-body")).getStatusCode().value()).isEqualTo(503);
            assertThat(global.handleIdentity(IdentityException.forbidden("secret-lifecycle")).getBody().message()).doesNotContain("secret");
            assertThat(global.handleMethodNotAllowed(new HttpRequestMethodNotSupportedException("DELETE")).getStatusCode().value()).isEqualTo(400);
            assertThat(global.handleUnsupportedMediaType(new HttpMediaTypeNotSupportedException("secret-media")).getStatusCode().value()).isEqualTo(400);
            assertThat(logs.list).isEmpty();
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST", "/api/other")));
            assertThat(global.handleMethodNotAllowed(new HttpRequestMethodNotSupportedException("DELETE")).getStatusCode().value()).isEqualTo(405);
            assertThat(global.handleUnsupportedMediaType(new HttpMediaTypeNotSupportedException("text/plain")).getStatusCode().value()).isEqualTo(415);
        } finally {
            RequestContextHolder.resetRequestAttributes();
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    private KnowledgeGapRows.Receipt receipt(KnowledgeGapState state, UUID solution) {
        return new KnowledgeGapRows.Receipt(11, state == KnowledgeGapState.SUBMITTED ? 0 : 2, state, UUID.randomUUID(), solution, false);
    }

    private static String creation() {
        return "{\"category\":\"OTHER\",\"description\":\"明确分享描述\",\"source\":\"MANUAL\",\"shareAcknowledged\":true}";
    }

    private static String reason() {
        return "{\"expectedVersion\":1,\"reason\":\"原因\"}";
    }
}
