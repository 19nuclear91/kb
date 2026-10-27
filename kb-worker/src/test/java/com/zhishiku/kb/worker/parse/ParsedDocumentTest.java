package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.model.DocumentBlock;
import com.zhishiku.kb.worker.parse.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParsedDocumentTest {

    @Test
    void extractsStructureAndSourceCoordinatesFromPlainTextCompatibilityInput() {
        ParsedDocument document = ParsedDocument.fromPlainText("guide.md", "text/markdown", """
                # 总则
                
                第一段说明。
                - 第一项
                
                | 名称 | 值 |
                | --- | --- |
                | 模式 | 本地 |
                """);

        assertThat(document.blocks()).extracting(DocumentBlock::kind)
                .containsExactly(DocumentBlock.Kind.HEADING,
                        DocumentBlock.Kind.PARAGRAPH,
                        DocumentBlock.Kind.LIST_ITEM,
                        DocumentBlock.Kind.TABLE);
        assertThat(document.blocks().get(1).headingPath()).isEqualTo("总则");
        assertThat(document.blocks().get(3).tableCells())
                .contains(List.of("模式", "本地"));
        assertThat(document.blocks().get(3).source().lineStart()).isEqualTo(6);
        assertThat(document.plainText()).contains("第一段说明").contains("模式");
    }

    @Test
    void retainsPageBreaksAndTruncationIssue() {
        ParsedDocument document = ParsedDocument.fromPlainText(
                "pages.txt", "text/plain", "第一页\f第二页", true);

        assertThat(document.blocks()).hasSize(2);
        assertThat(document.blocks().get(1).pageNo()).isEqualTo(2);
        assertThat(document.truncated()).isTrue();
        assertThat(document.issues()).singleElement()
                .extracting(issue -> issue.code()).isEqualTo("TEXT_TRUNCATED");
    }

    @Test
    void normalizesParserMetadataAndKeepsLegacyConstructorCompatible() {
        ParsedDocument legacy = new ParsedDocument("guide", "text/plain", List.of(),
                List.of(), false);
        assertThat(legacy.parserName()).isEqualTo("legacy-text");
        assertThat(legacy.parserVersion()).isEqualTo("1");

        ParsedDocument normalized = legacy.withParser("  external-parser  ", " 1.0 ");
        assertThat(normalized.parserName()).isEqualTo("external-parser");
        assertThat(normalized.parserVersion()).isEqualTo("1.0");

        ParsedDocument unknown = legacy.withParser(" ", null);
        assertThat(unknown.parserName()).isEqualTo("unknown");
        assertThat(unknown.parserVersion()).isEqualTo("unknown");
    }
}
