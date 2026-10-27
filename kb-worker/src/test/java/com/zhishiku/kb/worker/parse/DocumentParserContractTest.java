package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.model.ParseIssue;
import com.zhishiku.kb.worker.parse.model.ParsedDocument;
import com.zhishiku.kb.worker.parse.model.SourceLocation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParserContractTest {

    @Test
    void legacyTextParserIsWrappedWithoutLosingCompatibility() throws Exception {
        DocumentParser parser = (input, fileName, mimeType, maxChars) ->
                new String(input.readAllBytes(), StandardCharsets.UTF_8);

        ParsedDocument document = parser.parseDocument(
                new ByteArrayInputStream("兼容文本".getBytes(StandardCharsets.UTF_8)),
                "legacy.txt", "text/plain", 1000);

        assertThat(document.parserName()).isEqualTo("legacy-text");
        assertThat(document.blocks()).singleElement()
                .extracting(block -> block.kind().name()).isEqualTo("PARAGRAPH");
    }

    @Test
    void legacyEmptyTextCannotBecomeSuccessfulEmptyResult() throws Exception {
        DocumentParser parser = (input, fileName, mimeType, maxChars) -> "";

        ParsedDocument document = parser.parseDocument(
                new ByteArrayInputStream(new byte[0]), "empty.txt", "text/plain", 1000);

        assertThat(document.hasErrors()).isTrue();
        assertThat(document.issues()).extracting(ParseIssue::code)
                .containsExactly("EMPTY_CONTENT");
    }

    @Test
    void warningAndUnknownSourceCoordinatesRemainVisible() {
        ParsedDocument document = ParsedDocument.fromPlainText(
                "truncated.txt", "text/plain", "内容", true);

        assertThat(document.truncated()).isTrue();
        assertThat(document.issues()).singleElement()
                .satisfies(issue -> {
                    assertThat(issue.severity()).isEqualTo(ParseIssue.Severity.WARNING);
                    assertThat(issue.location()).isEqualTo(
                            SourceLocation.unknown());
                });
    }

    @Test
    void boundsDiagnosticFieldsWithoutPersistingUnboundedText() {
        ParseIssue issue = new ParseIssue(ParseIssue.Severity.WARNING,
                "c".repeat(200), "m".repeat(5000),
                new SourceLocation(
                        null, null, null, null, null, "l".repeat(2000)));

        assertThat(issue.code()).hasSize(128);
        assertThat(issue.message()).hasSize(4096);
        assertThat(issue.location().locator()).hasSize(1024);
    }
}
