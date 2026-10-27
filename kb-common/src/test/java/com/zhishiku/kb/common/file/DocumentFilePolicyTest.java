package com.zhishiku.kb.common.file;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentFilePolicyTest {
    @Test
    void rejectsActiveTypesAndMismatchedSignatures() {
        for (String name : new String[]{"attack.html", "attack.svg", "attack.exe", "attack.docm", "fake.pdf"}) {
            assertThatThrownBy(() -> inspect("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8), name))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> inspect(new byte[]{0, 1, 2}, "fake.txt")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detectsTextAndPdfWithoutClientMime() throws Exception {
        assertThat(inspect("中文正文".getBytes(StandardCharsets.UTF_8), "readme.md")).isEqualTo("text/plain");
        assertThat(inspect("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), "readme.pdf")).isEqualTo("application" +
                "/pdf");
    }

    @Test
    void acceptsOrdinaryOfficeAndRejectsWrongType() throws Exception {
        byte[] document = office(Map.of());
        assertThat(inspect(document, "doc.docx")).contains("wordprocessingml");
        assertThatThrownBy(() -> inspect(document, "doc.xlsx")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMacrosEmbeddedObjectsExternalResourcesAndEntities() throws Exception {
        for (var extra : List.of(
                Map.of("word/vbaProject.bin", "macro"),
                Map.of("word/embeddings/file.bin", "attachment"),
                Map.of("word/_rels/document.xml.rels", """
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Type="http://example/image" TargetMode="External" Target="http://127.0.0.1/secret"/>
                        </Relationships>
                        """),
                Map.of("word/_rels/document.xml.rels", """
                        <!DOCTYPE r [<!ENTITY leak SYSTEM "file:///must-not-read">]>
                        <Relationships>&leak;</Relationships>
                        """))) {
            assertThatThrownBy(() -> inspect(office(extra), "doc.docx")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void keepsHarmlessHyperlinksWithoutFetchingThem() throws Exception {
        assertThat(inspect(office(Map.of("word/_rels/document.xml.rels", """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
                    TargetMode="External" Target="https://example.invalid"/>
                </Relationships>
                """)), "doc.docx")).contains("wordprocessingml");
    }

    @Test
    void limitsActualBytesAndArchiveExpansion() throws Exception {
        assertThatThrownBy(() -> DocumentFilePolicy.inspect(new ByteArrayInputStream(new byte[20]), "a.txt", 10))
                .isInstanceOfAny(IOException.class, IllegalArgumentException.class);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < 101; i++) zip.write(block);
        }
        assertThatThrownBy(() -> inspect(bytes.toByteArray(), "bomb.docx"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("解压");
    }

    @Test
    void previewRejectsActiveAndForgedLegacyMime() {
        assertThatThrownBy(() -> DocumentFilePolicy.previewMime("a.html", "text/html", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocumentFilePolicy.previewMime("a.pdf", "application/pdf", "<html>".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(DocumentFilePolicy.previewMime("a.txt", "text/plain", "<html>".getBytes())).startsWith("text/plain");
    }

    private String inspect(byte[] bytes, String name) throws IOException {
        return DocumentFilePolicy.inspect(new ByteArrayInputStream(bytes), name, 50L * 1024 * 1024);
    }

    private static byte[] office(Map<String, String> extra) throws IOException {
        var files = new LinkedHashMap<String, String>();
        files.put("[Content_Types].xml", """
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Override PartName="/word/document.xml"
                    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """);
        files.put("word/document.xml", "<document/>");
        files.putAll(extra);
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}

