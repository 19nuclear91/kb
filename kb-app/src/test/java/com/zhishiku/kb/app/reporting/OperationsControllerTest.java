package com.zhishiku.kb.app.reporting;

import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.web.AdminQueryAdvice;
import com.zhishiku.kb.app.reporting.mapper.OperationsReportMapper;
import com.zhishiku.kb.app.reporting.service.OperationsReportService;
import com.zhishiku.kb.app.reporting.web.OperationsReportController;
import com.zhishiku.kb.app.reporting.web.OperationsReportResponseFilter;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OperationsControllerTest {
    @Test
    void safeErrorsAndEarlyDenialHaveNoStore() throws Exception {
        var service = mock(OperationsReportService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new OperationsReportController(service))
                .setControllerAdvice(new AdminQueryAdvice()).addFilters(new OperationsReportResponseFilter()).build();
        for (var failure : Map.of(400, QueryException.invalid(), 401, IdentityException.unauthorized("private-token"),
                403, QueryException.forbidden(), 503, new DataAccessResourceFailureException("private-db")).entrySet()) {
            doThrow(failure.getValue()).when(service).read(any());
            mvc.perform(get("/api/admin/reports/operations"))
                    .andExpect(status().is(failure.getKey())).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string(not(containsString("private-"))));
        }
        var denied = MockMvcBuilders.standaloneSetup(new OperationsReportController(service))
                .addFilters(new OperationsReportResponseFilter(), (request, response, chain) ->
                        ((jakarta.servlet.http.HttpServletResponse) response).sendError(401)).build();
        denied.perform(get("/api/admin/reports/operations")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void realProjectionAndQueryValidationDiscardUnexpectedSourceFields() throws Exception {
        var mapper = mock(OperationsReportMapper.class);
        when(mapper.asOf()).thenReturn(Instant.parse("2026-10-27T00:00:00Z"));
        when(mapper.documents()).thenReturn(Map.of("documentCount", "9007199254740993", "archivedDocumentCount", "0", "name", "private-name"));
        when(mapper.versions()).thenReturn(List.of());
        when(mapper.coverage()).thenReturn(List.of());
        when(mapper.pendingReviews()).thenReturn(Map.of("pendingReviewCount", "0"));
        var service = new OperationsReportService(mapper, mock(AdminQueryAccess.class));
        var mvc = MockMvcBuilders.standaloneSetup(new OperationsReportController(service))
                .setControllerAdvice(new AdminQueryAdvice()).build();
        mvc.perform(get("/api/admin/reports/operations"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.inventory.documentCount").value("9007199254740993"))
                .andExpect(jsonPath("$.data.review.approvedCount").value(nullValue()))
                .andExpect(content().string(not(containsString("private-name"))));
        mvc.perform(get("/api/admin/reports/operations").param("from", "a", "b")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/reports/operations").param("spaceId", "7")).andExpect(status().isBadRequest());
    }
}
