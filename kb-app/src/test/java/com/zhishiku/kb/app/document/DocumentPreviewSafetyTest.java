package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.document.model.DocumentFile;
import com.zhishiku.kb.app.document.service.DocumentCitationService;
import com.zhishiku.kb.app.document.service.DocumentManagementService;
import com.zhishiku.kb.app.document.service.DocumentUploadService;
import com.zhishiku.kb.app.document.web.DocumentController;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentPreviewSafetyTest {
    @Test
    void unsupportedPreviewClosesStreamAndDoesNotBecomeDownload() throws Exception {
        var manager = mock(DocumentManagementService.class);
        var file = file("attack.html", "text/html");
        var input = spy(new ByteArrayInputStream("<script>alert(1)</script>".getBytes()));
        when(manager.open(file)).thenReturn(input);
        var controller = new DocumentController(mock(DocumentUploadService.class), manager,
                mock(DocumentCitationService.class));
        var stream = DocumentController.class.getDeclaredMethod("stream", DocumentFile.class, boolean.class);
        stream.setAccessible(true);
        assertThatThrownBy(() -> stream.invoke(controller, file, true))
                .hasCauseInstanceOf(ApiException.class);
        verify(input).close();
    }

    @Test
    void textIsPlainAndSandboxedWhileDownloadsAreAttachments() throws Exception {
        var manager = mock(DocumentManagementService.class);
        var file = file("text.txt", "text/plain");
        when(manager.open(file)).thenAnswer(call -> new ByteArrayInputStream("<script>text</script>".getBytes()));
        var controller = new DocumentController(mock(DocumentUploadService.class), manager,
                mock(DocumentCitationService.class));
        var stream = DocumentController.class.getDeclaredMethod("stream", DocumentFile.class, boolean.class);
        stream.setAccessible(true);
        for (boolean inline : new boolean[]{true, false}) {
            var response = (ResponseEntity<?>) stream.invoke(controller, file, inline);
            assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox", "default-src " +
                    "'none'");
            assertThat(response.getHeaders().getContentDisposition().getType())
                    .isEqualTo(inline ? "inline" : "attachment");
            assertThat(response.getHeaders().getContentType().toString())
                    .startsWith(inline ? "text/plain" : "application/octet-stream");
            ((StreamingResponseBody) response.getBody())
                    .writeTo(new ByteArrayOutputStream());
        }
    }

    private DocumentFile file(String name, String mime) {
        var file = new DocumentFile();
        file.setFileName(name);
        file.setMimeType(mime);
        return file;
    }
}
