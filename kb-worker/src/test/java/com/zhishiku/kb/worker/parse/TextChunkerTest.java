package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.model.DocumentBlock;
import com.zhishiku.kb.worker.parse.model.ParsedDocument;
import com.zhishiku.kb.worker.parse.model.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {

    @Test
    void splitsAtReadableBoundariesAndKeepsProgress() {
        TextChunker chunker = new TextChunker(100, 20);
        var chunks = chunker.split("第一段内容。第二段内容。第三段内容。第四段内容。第五段内容。");

        assertThat(chunks).isNotEmpty().allSatisfy(c -> assertThat(c.content()).isNotBlank());
        assertThat(chunks.stream().map(TextChunker.Chunk::content).toList())
                .allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(100));
    }

    @Test
    void blankDocumentProducesNoChunks() {
        assertThat(new TextChunker(200, 20).split(" \n\t ")).isEmpty();
    }

    @Test
    void carriesHeadingPageAndSourceFromIntermediateModel() {
        ParsedDocument document = new ParsedDocument("guide", "text/plain", List.of(
                new DocumentBlock(DocumentBlock.Kind.PARAGRAPH, "正文内容。", 3,
                        "总则", new SourceLocation(3, 12, 12, 100, 106, "paragraph-1"))),
                List.of(), false);

        var chunks = new TextChunker(100, 10).split(document);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.pageNo()).isEqualTo(3);
            assertThat(chunk.headingPath()).isEqualTo("总则");
            assertThat(chunk.source().lineStart()).isEqualTo(12);
            assertThat(chunk.source().locator()).isEqualTo("paragraph-1");
        });
    }

    @Test
    void preservesStructureTypesAndSplitsOversizedTablesByRows() {
        ParsedDocument document = new ParsedDocument("table.md", "text/markdown", List.of(
                new DocumentBlock(DocumentBlock.Kind.TABLE,
                        "| 名称 | 值 |\n| 模式 | " + "本地".repeat(35) + " |\n| 备注 | "
                                + "可追溯".repeat(35) + " |",
                        2, "配置", new SourceLocation(2, 4, 6, 30, 500, "table-1"),
                        List.of(
                                List.of("名称", "值"),
                                List.of("模式", "本地".repeat(35)),
                                List.of("备注", "可追溯".repeat(35))))),
                List.of(), false);

        var chunks = new TextChunker(100, 10).split(document);

        assertThat(chunks).isNotEmpty().allSatisfy(chunk -> {
            assertThat(chunk.kind()).isEqualTo(DocumentBlock.Kind.TABLE);
            assertThat(chunk.tableCells()).isNotEmpty();
            assertThat(chunk.source().locator()).isEqualTo("table-1");
            assertThat(chunk.content().length()).isLessThanOrEqualTo(100);
        });
        assertThat(chunks.stream().flatMap(chunk -> chunk.tableCells().stream()).toList())
                .contains(List.of("名称", "值"))
                .contains(List.of("模式", "本地".repeat(35)))
                .contains(List.of("备注", "可追溯".repeat(35)));
    }
}
