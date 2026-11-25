package com.zhishiku.kb.app.auth.service.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosticInputTest {
    @Test
    void rejectsDuplicateUnknownIdentityAndVersionOverridesAndImpreciseIds() {
        for (String input : new String[]{"{\"userId\":1,\"userId\":2}", "{\"userId\":1.0}", "{\"userId\":-1}",
                "{\"userId\":9223372036854775808}", "{\"userId\":\"1\"}", "{\"userId\":1,\"roleIds\":[]}",
                "{\"userId\":1,\"sourceVersionId\":2}", "{\"userId\":1,\"before\":{}}", "{\"userId\":1} {}"}) {
            assertThatThrownBy(() -> DiagnosticInput.request(read(input), "SPACE", false)).isInstanceOf(DiagnosticException.class);
        }
        assertThat(DiagnosticInput.request(read("{\"userId\":9223372036854775807}"), "SPACE", false).userId()).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> DiagnosticInput.read(new ByteArrayInputStream(new byte[65]), 64))
                .isInstanceOfSatisfying(DiagnosticException.class,
                        error -> assertThat(error.reason()).isEqualTo(DiagnosticException.Reason.PAYLOAD_TOO_LARGE));
    }

    @Test
    void validatesFullCartesianGrantAndExactRevocationLimits() {
        ObjectMapper json = new ObjectMapper();
        var request = json.createObjectNode().put("userId", 8);
        var intent = request.putObject("intent").put("type", "SPACE_GRANT");
        var principals = intent.putArray("principals");
        for (int i = 1; i <= 50; i++) {
            principals.addObject().put("principalType", "ROLE").put("principalId", i);
        }
        var permissions = intent.putArray("permissions");
        DiagnosticEvaluator.SPACE_PERMISSIONS.forEach(permissions::add);
        assertThat(DiagnosticInput.request(request, "SPACE", true).intent().entries()).hasSize(300);
        principals.addObject().put("principalType", "ROLE").put("principalId", 51);
        assertInvalid(request, "SPACE");
        principals.remove(50);
        principals.set(49, principals.get(0));
        assertInvalid(request, "SPACE");
        request.remove("intent");
        var revoke = request.putObject("intent").put("type", "SPACE_REVOKE").putArray("items");
        for (int i = 1; i <= 300; i++) {
            revoke.addObject().put("principalType", "DEPT").put("principalId", i).put("permission", i % 2 == 0 ? "view" : "download");
        }
        assertThat(DiagnosticInput.request(request, "SPACE", true).intent().entries()).hasSize(300);
        revoke.addObject().put("principalType", "DEPT").put("principalId", 301).put("permission", "view");
        assertInvalid(request, "SPACE");
        revoke.remove(300);
        revoke.set(299, revoke.get(0));
        assertInvalid(request, "SPACE");
    }

    @Test
    void documentRejectsSpaceIntentReviewManageAndAdditionalDocument() {
        for (String permission : new String[]{"view", "download", "edit", "comment", "review", "manage"}) {
            var request = read("{\"userId\":8,\"intent\":{\"type\":\"DOCUMENT_GRANT\",\"principalType\":\"USER\",\"principalId\":8,\"permission\":\"" + permission + "\"}}");
            if (permission.equals("review") || permission.equals("manage")) {
                assertInvalid(request, "DOCUMENT");
            } else {
                assertThat(DiagnosticInput.request(request, "DOCUMENT", true).intent().entries()).hasSize(1);
            }
            assertInvalid(request, "SPACE");
        }
        assertThatThrownBy(() -> DiagnosticInput.request(read("{\"userId\":1,\"documentId\":2}"), "DOCUMENT", false))
                .isInstanceOf(DiagnosticException.class);
        assertThat(DiagnosticInput.search(read("{\"q\":\"%_名称\",\"limit\":100}")).q()).isEqualTo("%_名称");
        assertThatThrownBy(() -> DiagnosticInput.search(read("{\"limit\":101}"))).isInstanceOf(DiagnosticException.class);
    }

    static JsonNode read(String input) {
        return DiagnosticInput.read(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), 65536);
    }

    private static void assertInvalid(JsonNode request, String kind) {
        assertThatThrownBy(() -> DiagnosticInput.request(request, kind, true)).isInstanceOf(DiagnosticException.class);
    }
}
