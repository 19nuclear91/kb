package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.worker.parse.model.ParseIssue;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import com.zhishiku.kb.worker.parse.model.ParsedDocument;
import com.zhishiku.kb.worker.storage.ObjectStorage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultParsePipelineTest {

    @Test
    void refusesMissingIdentityAndWrongGeneratedProfile() throws Exception {
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentParser parser = mock(DocumentParser.class);
        var registry = mock(EmbeddingClientRegistry.class);
        var pipeline = new DefaultParsePipeline(storage, parser,
                new IngestionProperties(1024, 10000, 100, 5), registry);
        var task = new ParseTask(1L, 2L, 1, 3, OffsetDateTime.now(), "owner",
                "key", "a.txt", 100L, "text/plain");
        assertThatThrownBy(() -> pipeline.process(task, () -> true)).isInstanceOf(LeaseLostException.class);
        verifyNoInteractions(storage, parser, registry);

        when(parser.isAvailable()).thenReturn(true);
        var client = mock(ProfileEmbeddingClient.class);
        when(registry.find(anyString(), anyString())).thenReturn(Optional.of(client));
        when(client.role()).thenReturn(EmbeddingRole.DOCUMENT);
        when(client.profile()).thenReturn(ParseEmbeddingFixture.PROFILE);
        when(storage.get(anyString())).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(parser.parseDocument(any(), anyString(), anyString(), anyInt()))
                .thenReturn(ParsedDocument.fromPlainText("a.txt", "text/plain", "text", false));
        when(client.embed(anyList())).thenReturn(new EmbeddingResult(
                ParseEmbeddingFixture.PROFILE, EmbeddingRole.QUERY,
                List.of(ParseEmbeddingFixture.vector())));
        assertThatThrownBy(() -> pipeline.process(ParseEmbeddingFixture.task(), () -> true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity, role or count");
    }

    @Test
    void downloadsParsesChunksAndEmbedsBeforeReturningResult() throws Exception {
        ObjectStorage storage = key -> new ByteArrayInputStream("标题\n这是第一段内容。\n这是第二段内容。".getBytes());
        DocumentParser parser = (input, fileName, mimeType, maxChars) ->
                new String(input.readAllBytes());
        var embeddings = ParseEmbeddingFixture.clients();
        var pipeline = new DefaultParsePipeline(storage, parser,
                new IngestionProperties(1024 * 1024, 10000, 20, 5), embeddings);
        var task = ParseEmbeddingFixture.task(1L, 2L,
                "objects/doc", "doc.txt", 100L, "text/plain");

        var result = pipeline.process(task, () -> true);

        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks()).allSatisfy(c -> assertThat(c.embedding()).hasSize(1024));
        assertThat(result.execution().matches(task, "owner")).isTrue();
        assertThat(result.parserName()).isEqualTo("legacy-text");
        assertThat(result.parserVersion()).isEqualTo("1");
    }

    @Test
    void carriesWarningsAndParserMetadataIntoPipelineResult() throws Exception {
        ObjectStorage storage = key -> new ByteArrayInputStream("正文".getBytes());
        DocumentParser parser = new DocumentParser() {
            @Override
            public String parse(InputStream input, String fileName, String mimeType,
                                int maxExtractedChars) {
                return "正文";
            }

            @Override
            public ParsedDocument parseDocument(InputStream input, String fileName,
                                                String mimeType, int maxExtractedChars) {
                return ParsedDocument.fromPlainText(fileName, mimeType, "正文", true)
                        .withParser("fixture", "7");
            }
        };
        var embeddings = ParseEmbeddingFixture.clients();
        var pipeline = new DefaultParsePipeline(storage, parser,
                new IngestionProperties(1024 * 1024, 10000, 100, 5), embeddings);
        var task = ParseEmbeddingFixture.task(2L, 3L,
                "objects/doc", "doc.txt", 100L, "text/plain");

        var result = pipeline.process(task, () -> true);

        assertThat(result.parserName()).isEqualTo("fixture");
        assertThat(result.parserVersion()).isEqualTo("7");
        assertThat(result.truncated()).isTrue();
        assertThat(result.issues()).extracting(ParseIssue::code).containsExactly("TEXT_TRUNCATED");
    }

    @Test
    void rejectsErrorResultsBeforeEmbedding() {
        ObjectStorage storage = key -> new ByteArrayInputStream("正文".getBytes());
        DocumentParser parser = new DocumentParser() {
            @Override
            public String parse(InputStream input, String fileName, String mimeType,
                                int maxExtractedChars) {
                return "正文";
            }

            @Override
            public ParsedDocument parseDocument(InputStream input, String fileName,
                                                String mimeType, int maxExtractedChars) {
                return new ParsedDocument(fileName, mimeType, List.of(),
                        List.of(new ParseIssue(ParseIssue.Severity.ERROR,
                                "UNSUPPORTED", "不支持", null)), false)
                        .withParser("fixture", "7");
            }
        };
        var embeddings = ParseEmbeddingFixture.clients();
        var pipeline = new DefaultParsePipeline(storage, parser,
                new IngestionProperties(1024 * 1024, 10000, 100, 5), embeddings);
        var task = ParseEmbeddingFixture.task(3L, 4L,
                "objects/doc", "doc.txt", 100L, "text/plain");

        assertThatThrownBy(() -> pipeline.process(task, () -> true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNSUPPORTED");
    }
}
