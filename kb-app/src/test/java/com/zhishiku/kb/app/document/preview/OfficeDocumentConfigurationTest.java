package com.zhishiku.kb.app.document.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.model.DocumentFile;
import com.zhishiku.kb.common.office.OfficeServiceSignature;
import com.zhishiku.kb.common.office.OfficeCrypto;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class OfficeDocumentConfigurationTest {
    private static final String ID = "a".repeat(32);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void configurationContainsOnlyReadOnlyCapabilitiesAndNoLoginOrStorageKey() throws Exception {
        var settings = OfficePreviewSettingsTest.valid();
        var json = new ObjectMapper();
        var configuration = new OfficeDocumentConfiguration(settings, json, CLOCK);
        long now = CLOCK.millis();
        var session = new OfficePreviewSession(ID, 7, "private-original-login", 8, 9, "kb-" + "b".repeat(64),
                "kb", "ACTIVE", "", "cookie-hash", now, now + 1800000, now + 45000);
        var file = new DocumentFile(8L, 9L, 1, "s3-private-object", "<script>.docx", 10L, "application/octet-stream");
        var granted = new OfficePreviewSessions.Authorized(session, file);
        var config = configuration.configuration(granted);
        var tree = json.valueToTree(config);
        assertThat(tree.at("/editorConfig/mode").asText()).isEqualTo("view");
        assertThat(tree.at("/editorConfig/coEditing/mode").asText()).isEqualTo("strict");
        for (var field : new String[]{"edit", "download", "print", "comment", "review", "fillForms", "chat"}) {
            assertThat(tree.at("/document/permissions/" + field).asBoolean(true)).isFalse();
        }
        assertThat(json.writeValueAsString(config)).doesNotContain("private-original-login", "s3-private-object", "cookie-hash");
        assertThat(configuration.configuration(granted).get("token")).isEqualTo(config.get("token"));
        OfficePreviewSessionsTest.fail(() -> configuration.requireSourceSignature(ID, "Bearer " + config.get("token")), 401);
    }

    @Test
    void sourceTokensBindPurposeTargetExpiryAndAnIndependentSecret() {
        var settings = OfficePreviewSettingsTest.valid();
        var configuration = new OfficeDocumentConfiguration(settings, new ObjectMapper(), CLOCK);
        long now = CLOCK.instant().getEpochSecond();
        Map<String, Object> claims = Map.of("operation", "download", "payload", Map.of("url", configuration.sourceUrl(ID)),
                "iat", now, "exp", now + 60);
        String signature = configuration.sign(claims, settings.getOutboxSecret());
        configuration.requireSourceSignature(ID, "Bearer " + signature);
        OfficePreviewSessionsTest.fail(() -> configuration.requireSourceSignature("b".repeat(32), "Bearer " + signature), 401);
        OfficePreviewSessionsTest.fail(() -> configuration.requireSourceSignature(ID, "Bearer "
                + configuration.sign(claims, settings.getConfigurationSecret())), 401);
        OfficePreviewSessionsTest.fail(() -> configuration.requireSourceSignature(ID, "Bearer "
                + configuration.sign(Map.of("operation", "callback", "payload", Map.of("url", configuration.sourceUrl(ID)),
                "iat", now, "exp", now + 60), settings.getOutboxSecret())), 401);
        OfficePreviewSessionsTest.fail(() -> configuration.requireSourceSignature(ID, "Bearer "
                + configuration.sign(Map.of("operation", "download", "payload", Map.of("url", configuration.sourceUrl(ID)),
                "iat", now - 70, "exp", now - 10), settings.getOutboxSecret())), 401);
        OfficePreviewSessionsTest.fail(() -> configuration.requireSourceSignature(ID, null), 401);
        String body = signature.split("\\.")[1];
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String downgrade = header + "." + body;
        OfficePreviewSessionsTest.fail(() -> configuration.requireSourceSignature(ID, "Bearer " + downgrade + "."
                + OfficeCrypto.hmac(settings.getOutboxSecret(), downgrade)), 401);
    }

    @Test
    void internalProofCannotChangeOperationBodyOrOutliveItsWindow() {
        String path = "/internal/office-preview/authorize";
        byte[] body = "{\"sessionId\":\"id\"}".getBytes(StandardCharsets.UTF_8);
        String secret = "s".repeat(32);
        String proof = OfficeServiceSignature.sign(secret, path, body, CLOCK);
        assertThat(OfficeServiceSignature.verify(secret, path, body, proof, CLOCK)).isTrue();
        assertThat(OfficeServiceSignature.verify(secret, "/internal/office-preview/bootstrap", body, proof, CLOCK)).isFalse();
        assertThat(OfficeServiceSignature.verify(secret, path, "changed".getBytes(StandardCharsets.UTF_8), proof, CLOCK)).isFalse();
        assertThat(OfficeServiceSignature.verify(secret, path, body, proof, Clock.offset(CLOCK, java.time.Duration.ofSeconds(31)))).isFalse();
    }
}
