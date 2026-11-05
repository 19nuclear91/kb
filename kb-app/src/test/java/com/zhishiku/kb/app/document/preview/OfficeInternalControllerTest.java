package com.zhishiku.kb.app.document.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.model.DocumentFile;
import com.zhishiku.kb.app.document.service.DocumentManagementService;
import com.zhishiku.kb.common.office.OfficeServiceSignature;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OfficeInternalControllerTest {
    @Test
    void onlyServiceProofCanActivateAndCallbackHasNoWriteEntry() throws Exception {
        var settings = OfficePreviewSettingsTest.valid();
        var sessions = mock(OfficePreviewSessions.class);
        var documents = mock(DocumentManagementService.class);
        var json = new ObjectMapper();
        var controller = new OfficeInternalController(settings, sessions,
                new OfficeDocumentConfiguration(settings, json), documents, json);
        String path = "/internal/office-preview/bootstrap";
        byte[] body = json.writeValueAsBytes(Map.of("sessionId", "a".repeat(32), "launchToken", "proof", "origin", settings.getApplicationOrigin()));
        var invalidRequest = new MockHttpServletRequest("POST", path);
        invalidRequest.setContent(body);
        OfficePreviewSessionsTest.fail(() -> controller.operation("bootstrap", invalidRequest), 401);
        verifyNoInteractions(sessions, documents);
        var request = new MockHttpServletRequest("POST", path);
        request.setContent(body);
        request.addHeader(OfficeServiceSignature.HEADER, OfficeServiceSignature.sign(settings.getGatewaySecret(), path, body, Clock.systemUTC()));
        controller.operation("bootstrap", request);
        verify(sessions).activate("a".repeat(32), "proof", settings.getApplicationOrigin());
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new OfficePreviewAdvice()).build();
        mvc.perform(post("/internal/office-preview/callback").contentType("application/json")
                .content("{\"status\":2,\"url\":\"http://untrusted.invalid/file\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(documents);
    }

    @Test
    void sourceChecksServiceSignatureThenCurrentAccessAndStreamsOnlyBoundFile() throws Exception {
        var settings = OfficePreviewSettingsTest.valid();
        var sessions = mock(OfficePreviewSessions.class);
        var documents = mock(DocumentManagementService.class);
        var config = mock(OfficeDocumentConfiguration.class);
        var controller = new OfficeInternalController(settings, sessions, config, documents, new ObjectMapper());
        String id = "a".repeat(32);
        var request = new MockHttpServletRequest("GET", "/internal/office-preview/sessions/" + id + "/source");
        request.addHeader("Authorization", "Bearer supplier-proof");
        byte[] bytes = document();
        var file = new DocumentFile(8L, 9L, 1, "private-object", "file.docx", (long) bytes.length, "application/octet-stream");
        when(sessions.source(id)).thenReturn(new OfficePreviewSessions.Authorized(null, file));
        when(documents.open(file)).thenAnswer(call -> new ByteArrayInputStream(bytes));
        var response = controller.source(id, request);
        var output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        assertThat(output.toByteArray()).isEqualTo(bytes);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(config).requireSourceSignature(id, "Bearer supplier-proof");
        when(documents.open(file)).thenReturn(new ByteArrayInputStream("not-a-document".getBytes(StandardCharsets.UTF_8)));
        OfficePreviewSessionsTest.fail(() -> controller.source(id, request), 415);
        when(documents.open(file)).thenThrow(new IllegalStateException("storage credentials must not escape"));
        OfficePreviewSessionsTest.fail(() -> controller.source(id, request), 503);
        request.setQueryString("extra=1");
        OfficePreviewSessionsTest.fail(() -> controller.source(id, request), 401);
        request.setQueryString(null);
        doThrow(new OfficePreviewFailure(404)).when(sessions).source(id);
        OfficePreviewSessionsTest.fail(() -> controller.source(id, request), 404);
    }

    private static byte[] document() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : Map.of("[Content_Types].xml", """
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """, "word/document.xml", "<document/>").entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
