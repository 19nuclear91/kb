package com.zhishiku.kb.app.space.web;

import com.zhishiku.kb.app.config.DocumentReviewResponseFilter;
import com.zhishiku.kb.app.space.model.SpacePublicationPolicy;
import com.zhishiku.kb.app.space.service.SpaceAuthorizer;
import com.zhishiku.kb.app.space.service.SpacePublicationPolicyService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpacePublicationPolicyControllerTest {
    private final SpacePublicationPolicyService service = mock(SpacePublicationPolicyService.class);
    private final SpaceAuthorizer authorizer = mock(SpaceAuthorizer.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SpacePublicationPolicyController(service, authorizer))
            .setControllerAdvice(new SpacePublicationPolicyAdvice()).addFilters(new DocumentReviewResponseFilter()).build();
    private static final String PATH = "/api/admin/spaces/3/publication-policy";

    @Test
    void readAndWriteKeepExactLargeIdentifiersAndNoStore() throws Exception {
        long id = 9007199254740993L;
        var result = new SpacePublicationPolicy(id, PublicationPolicy.REVIEW_REQUIRED, id);
        when(service.get(id)).thenReturn(result);
        mvc.perform(get("/api/admin/spaces/" + id + "/publication-policy"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.spaceId").value(Long.toString(id)))
                .andExpect(jsonPath("$.data.version").value(Long.toString(id)));
        when(service.update(id, PublicationPolicy.REVIEW_REQUIRED, id, "req")).thenReturn(result);
        mvc.perform(patch("/api/admin/spaces/" + id + "/publication-policy").contentType("application/json")
                        .header("X-Request-Id", "req").content("{\"policy\":\"REVIEW_REQUIRED\",\"expectedVersion\":\"" + id + "\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).update(id, PublicationPolicy.REVIEW_REQUIRED, id, "req");
    }

    @Test
    void invalidJsonUnknownDuplicateFieldsAndVersionsAreRejected() throws Exception {
        for (String body : new String[]{"{}", "[]", "{", "{\"policy\":\"AUTO\",\"expectedVersion\":0,\"actor\":7}",
                "{\"policy\":\"AUTO\",\"policy\":\"REVIEW_REQUIRED\",\"expectedVersion\":0}",
                "{\"policy\":\"auto\",\"expectedVersion\":0}", "{\"policy\":\"AUTO\",\"expectedVersion\":-1}",
                "{\"policy\":\"AUTO\",\"expectedVersion\":1.0}", "{\"policy\":\"AUTO\",\"expectedVersion\":\"01\"}",
                "{\"policy\":\"AUTO\",\"expectedVersion\":9223372036854775808}"}) {
            mvc.perform(patch(PATH).contentType("application/json").header("X-Request-Id", "req").content(body))
                    .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(get(PATH).param("unknown", "1", "2")).andExpect(status().isBadRequest());
        mvc.perform(patch(PATH).contentType("application/json").content("{\"policy\":\"AUTO\",\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/spaces/0/publication-policy")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void denialConflictAndDependencyFailureAreSafeAndNotCached() throws Exception {
        when(service.get(3)).thenThrow(IdentityException.forbidden("secret"));
        mvc.perform(get(PATH)).andExpect(status().isForbidden()).andExpect(jsonPath("$.data.errorCode").value("FORBIDDEN"));
        when(service.update(anyLong(), any(), anyLong(), any())).thenThrow(ApiException.conflict("secret"));
        mvc.perform(patch(PATH).contentType("application/json").header("X-Request-Id", "req")
                        .content("{\"policy\":\"AUTO\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.errorCode").value("STATE_CONFLICT"));
        doThrow(new IllegalStateException("private database failure")).when(service).get(3);
        mvc.perform(get(PATH)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.errorCode").value("SERVICE_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
