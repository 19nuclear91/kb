package com.zhishiku.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookTest extends TestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{\"version\":\"4\",\"version\":\"4\"}", "{", "{\"x\":NaN}"})
    void malformedJsonCannotSendPartially(String json) throws Exception {
        mvc.perform(post(json.getBytes(StandardCharsets.UTF_8))).andExpect(status().isBadRequest());
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsDuplicateExtensionFieldsAndTrailingValues() throws Exception {
        String json = webhook(1).toString();
        mvc.perform(post((json + "{}").getBytes(StandardCharsets.UTF_8))).andExpect(status().isBadRequest());
        mvc.perform(post((json.substring(0, json.length() - 1) + ",\"extension\":{\"x\":1,\"x\":2}}")
                .getBytes(StandardCharsets.UTF_8))).andExpect(status().isBadRequest());
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "status", "receiver", "groupKey", "groupLabels", "commonLabels", "commonAnnotations", "alerts"})
    void missingRequiredStructureIsRejected(String name) throws Exception {
        ObjectNode input = webhook(1);
        input.remove(name);
        mvc.perform(post(input)).andExpect(status().isBadRequest());
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "startsAt", "endsAt", "labels", "annotations"})
    void missingAlertStructureRejectsWholeGroup(String name) throws Exception {
        ObjectNode input = webhook(2);
        alert(input, 1).remove(name);
        mvc.perform(post(input)).andExpect(status().isBadRequest());
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"environment", "deployment", "service", "severity", "alertname"})
    void unexpectedOrMissingIdentityRejectsAllItems(String label) throws Exception {
        ObjectNode input = webhook(2);
        ((ObjectNode) alert(input, 1).get("labels")).put(label, "untrusted-canary");
        mvc.perform(post(input)).andExpect(status().isBadRequest());
        ((ObjectNode) alert(input, 1).get("labels")).remove(label);
        mvc.perform(post(input)).andExpect(status().isBadRequest());
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-date", "2025-02-30T12:00:00Z", "2025-01-01", "0001-01-01T00:00:00Z", "2025-01-01T00:00:00"})
    void validatesRfc3339Time(String value) throws Exception {
        ObjectNode input = webhook(1);
        alert(input, 0).put("startsAt", value);
        mvc.perform(post(input)).andExpect(status().isBadRequest());
        assertThat(calls).hasValue(0);
    }

    @Test
    void invalidStatesTruncationAndTimesRejectBeforeSend() throws Exception {
        ObjectNode input = webhook(1);
        input.put("status", "resolved");
        mvc.perform(post(input)).andExpect(status().isBadRequest());
        input.put("status", "unknown");
        mvc.perform(post(input)).andExpect(status().isBadRequest());
        input.put("status", "firing").put("truncatedAlerts", -1);
        mvc.perform(post(input)).andExpect(status().isBadRequest());
        input.put("truncatedAlerts", 0.5);
        mvc.perform(post(input)).andExpect(status().isBadRequest());
        input.put("truncatedAlerts", "0");
        mvc.perform(post(input)).andExpect(status().isBadRequest());
        input.put("truncatedAlerts", 0);
        alert(input, 0).put("endsAt", "2024-01-01T00:00:00Z");
        mvc.perform(post(input)).andExpect(status().isBadRequest());
        assertThat(calls).hasValue(0);
    }

    @Test
    void exactRequestByteLimitAndUnknownLengthAreBounded() throws Exception {
        byte[] source = BoundedJson.encode(webhook(1));
        byte[] exact = Arrays.copyOf(source, BoundedJson.REQUEST_BYTES);
        Arrays.fill(exact, source.length, exact.length, (byte) ' ');
        mvc.perform(post(exact)).andExpect(status().isNoContent());
        byte[] tooLarge = Arrays.copyOf(exact, exact.length + 1);
        mvc.perform(post(tooLarge)).andExpect(status().isPayloadTooLarge());
        org.springframework.mock.web.MockHttpServletRequest request = org.mockito.Mockito.spy(post(tooLarge)
                .buildRequest(new org.springframework.mock.web.MockServletContext()));
        request.setLocalPort(8090);
        org.mockito.Mockito.doReturn(-1L).when(request).getContentLengthLong();
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError();
        });
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(calls).hasValue(1);
    }

    @Test
    void unicodeCodePointsAreBoundedWithoutRejectingLegalSurrogatePairs() throws Exception {
        ObjectNode input = webhook(1);
        input.put("extension", "😀".repeat(4096));
        mvc.perform(post(input)).andExpect(status().isNoContent());
        input.put("extension", "😀".repeat(4097));
        mvc.perform(post(input)).andExpect(status().isPayloadTooLarge());
        input.put("extension", "\ud800");
        mvc.perform(post(input)).andExpect(status().isBadRequest());
        mvc.perform(post(new byte[]{(byte) 0xc3, 0x28})).andExpect(status().isBadRequest());
        assertThat(calls).hasValue(1);
    }

    @Test
    void depthAndAlertCountLimitsAreEnforced() throws Exception {
        ObjectNode input = webhook(100);
        mvc.perform(post(input)).andExpect(status().isNoContent());
        mvc.perform(post(webhook(101))).andExpect(status().isPayloadTooLarge());
        mvc.perform(post(webhook(0))).andExpect(status().isBadRequest());
        input = webhook(1);
        ObjectNode next = input;
        for (int index = 0; index < 15; index++) {
            next = next.putObject("extension");
        }
        next.put("leaf", "ok");
        mvc.perform(post(input)).andExpect(status().isNoContent());
        next.putObject("extra");
        mvc.perform(post(input)).andExpect(status().isPayloadTooLarge());
        assertThat(calls).hasValue(2);
    }
}
