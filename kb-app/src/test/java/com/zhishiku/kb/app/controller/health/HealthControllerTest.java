package com.zhishiku.kb.app.controller.health;

import com.zhishiku.kb.app.service.health.HealthFailure;
import com.zhishiku.kb.app.service.health.HealthReadService;
import com.zhishiku.kb.app.domain.vo.health.KnowledgeHealthRadarVo;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthControllerTest {
    @Test
    void successEnvelopeAndAllControlledFailuresUseActualHttpSemantics() throws Exception {
        var service = mock(HealthReadService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new HealthController(service)).setControllerAdvice(new HealthAdvice()).build();
        var counts = new KnowledgeHealthRadarVo.Counts("0", "0", "0", "0", "0", "0");
        when(service.read(eq(2L), any())).thenReturn(new KnowledgeHealthRadarVo("KNOWLEDGE_HEALTH_V1", "2",
                OffsetDateTime.parse("2026-11-27T08:00:00Z"), LocalDate.of(2026, 11, 27), "Asia/Shanghai", "ALL", 20,
                counts, "0", List.of(), false, null));
        mvc.perform(get("/api/admin/spaces/2/health-radar")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.affectedDocumentCount").value("0"))
                .andExpect(jsonPath("$.data.counts.reviewOverdue").value("0"))
                .andExpect(header().string("Cache-Control", "no-store"));
        for (var error : List.of(new HealthFailure(400, "INVALID_INPUT"), new HealthFailure(401, "UNAUTHORIZED"),
                new HealthFailure(403, "FORBIDDEN"), new HealthFailure(404, "NOT_FOUND"),
                new HealthFailure(409, "SPACE_ARCHIVED"), HealthFailure.unavailable())) {
            doThrow(error).when(service).read(eq(2L), any());
            mvc.perform(get("/api/admin/spaces/2/health-radar")).andExpect(status().is(error.status()))
                    .andExpect(header().string("X-Kb-Health-Error", error.reason()))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Test
    void inputIsStrictBeforeServiceCall() {
        var service = mock(HealthReadService.class);
        var controller = new HealthController(service);
        var request = new MockHttpServletRequest();
        request.addParameter("limit", "20", "21");
        assertThatThrownBy(() -> controller.read("1", request)).isInstanceOf(HealthFailure.class);
        assertThatThrownBy(() -> controller.read("0", new MockHttpServletRequest())).isInstanceOf(HealthFailure.class);
        verifyNoInteractions(service);
    }

    @Test
    void standaloneControllerReturnsSafeStatusAndExplicitConflictReason() throws Exception {
        var service = mock(HealthReadService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new HealthController(service)).setControllerAdvice(new HealthAdvice()).build();
        when(service.read(eq(2L), any())).thenThrow(new HealthFailure(409, "RADAR_DATE_CHANGED"));
        mvc.perform(get("/api/admin/spaces/2/health-radar")).andExpect(status().isConflict())
                .andExpect(header().string("X-Kb-Health-Error", "RADAR_DATE_CHANGED"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value(500));
        when(service.read(eq(2L), any())).thenThrow(new IllegalStateException("private hidden document"));
        var result = mvc.perform(get("/api/admin/spaces/2/health-radar")).andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private", "hidden");
        var advice = new HealthAdvice();
        assertThat(advice.failure(IdentityException.unauthorized("private")).getStatusCode().value()).isEqualTo(401);
        assertThat(advice.failure(IdentityException.passwordChangeRequired("private")).getStatusCode().value()).isEqualTo(403);
    }
}
