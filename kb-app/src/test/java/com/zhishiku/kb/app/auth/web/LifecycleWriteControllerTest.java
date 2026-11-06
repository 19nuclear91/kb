package com.zhishiku.kb.app.auth.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleMappingService;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleRestorationService;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleRetryService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LifecycleWriteControllerTest {
    @Test
    void restorationResultsAndAllExpectedFailuresAreSafeAndUncacheable() throws Exception {
        var service = mock(LifecycleRestorationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new LifecycleRestorationController(service))
                .setControllerAdvice(new LifecycleFeedbackAdvice()).build();
        try (var sessions = mockStatic(StpUtil.class)) {
            sessions.when(StpUtil::getTokenValue).thenReturn("token");
            when(service.restore(eq("token"), eq(7L), any())).thenReturn(
                    new LifecycleRestorationService.Restored(7, 2, 2, false, false, false, "RESTORED_MANUALLY_DISABLED"));
            String body = "{\"version\":1,\"mappingVersion\":1,\"proof\":\"private\",\"reason\":\"核对\",\"requestId\":\"request\"}";
            mvc.perform(post("/api/admin/identity/users/7/lifecycle/restore").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data.outcome").value("RESTORED_MANUALLY_DISABLED"))
                    .andExpect(jsonPath("$.data.enabled").value(false))
                    .andExpect(jsonPath("$.data.proof").doesNotExist());
            var failures = List.of(IdentityException.badRequest("private"), IdentityException.unauthorized("private"),
                    IdentityException.forbidden("private"), IdentityException.notFound("private"),
                    IdentityException.conflict(IdentityConflictReason.LIFECYCLE_CONFIRMATION_STALE, "private"),
                    ApiException.unavailable("private"));
            for (var failure : failures) {
                doThrow(failure).when(service).restore(eq("token"), eq(7L), any());
                var result = mvc.perform(post("/api/admin/identity/users/7/lifecycle/restore")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().is(failure.httpStatus())).andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
                if (failure.httpStatus() == 409) {
                    result.andExpect(jsonPath("$.reasonCode").value("LIFECYCLE_CONFIRMATION_STALE"));
                }
            }
        }
    }

    @Test
    void retryCannotSupplyReplacementPayloadAndMalformedVersionsAreRejected() throws Exception {
        var mappings = mock(LifecycleMappingService.class);
        var retry = mock(LifecycleRetryService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new LifecycleWriteController(mappings, retry))
                .setControllerAdvice(new LifecycleFeedbackAdvice()).build();
        mvc.perform(post("/api/admin/identity/lifecycle/events/11/retry").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"核对\",\"requestId\":\"id\",\"payload\":{\"employee\":\"private\"}}"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/admin/identity/users/7/lifecycle/verification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"broken\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mappings, retry);
    }
}
