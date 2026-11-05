package com.zhishiku.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class IngressTest extends TestSupport {
    @Test
    void confirmsExactlyOnceWithNoStoreAndEmptyResponse() throws Exception {
        mvc.perform(post(webhook(1)).header("Cookie", "satoken=user-canary").header("traceparent", "trace-canary")
                .header("baggage", "identity-canary")).andExpect(status().isNoContent()).andExpect(result -> {
                    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
                    assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
                });
        assertThat(calls).hasValue(1);
        assertThat(new String(lastPayload, StandardCharsets.UTF_8)).doesNotContain("identity-canary", "trace-canary", "satoken");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Bearer wrong-canary", "Basic credentials-canary", "bearer wrong-canary", "Bearer user-session-canary"})
    void authenticationPrecedesAllBodyReadsEvenWhenHuge(String authorization) throws Exception {
        MockHttpServletRequest request = spy(new MockHttpServletRequest("POST", IngressFilter.PATH));
        request.setLocalPort(8090);
        request.addHeader("satoken", "user-session-canary");
        if (!authorization.isEmpty()) {
            request.addHeader("Authorization", authorization);
        }
        request.setContent(new byte[BoundedJson.REQUEST_BYTES + 1]);
        request.setContentType("application/gzip");
        doThrow(new AssertionError("unauthenticated body was read")).when(request).getInputStream();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("unexpected route dispatch");
        });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    void duplicateAuthorizationAndUserTokenDoNotAuthenticate() throws Exception {
        mvc.perform(post(webhook(1)).header("Authorization", "Bearer " + BEARER)).andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.post(IngressFilter.PATH).with(request -> {
            request.setLocalPort(8090);
            return request;
        }).header("satoken", BEARER).content(BoundedJson.encode(webhook(1)))).andExpect(status().isUnauthorized());
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/swagger-ui.html", "/v3/api-docs", "/actuator/env", "/internal/alerts/dingtalk/", "/secret-path-canary"})
    void unknownRoutesAreFixedAndDoNotEchoPaths(String path) throws Exception {
        mvc.perform(MockMvcRequestBuilders.post(path).with(request -> {
            request.setLocalPort(8090);
            return request;
        })).andExpect(status().isNotFound()).andExpect(result -> {
            assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        });
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/json;charset=GBK", "application/json;unknown=value", "application/xml", "application/problem+json"})
    void rejectsUnsupportedMediaTypes(String type) throws Exception {
        mvc.perform(post(webhook(1)).contentType(type)).andExpect(status().isUnsupportedMediaType());
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsCompressionWrongMethodAndQuery() throws Exception {
        mvc.perform(post(webhook(1)).header("Content-Encoding", "gzip")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(post(webhook(1)).with(request -> {
            request.setMethod("GET");
            return request;
        })).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(webhook(1)).queryParam("token", "canary")).andExpect(status().isNotFound());
        assertThat(calls).hasValue(0);
    }
}
