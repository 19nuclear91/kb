package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.worker.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentParserConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DocumentParserConfiguration.class);

    @Test
    void missingAdapterReportsUnavailableWithoutReadingOriginal() {
        context.run(application -> {
            assertThat(application).hasSingleBean(DocumentParser.class);
            var parser = application.getBean(DocumentParser.class);
            assertThat(parser.isAvailable()).isFalse();
            InputStream input = mock(InputStream.class);
            assertThatThrownBy(() -> parser.parseDocument(input, "document.pdf", "application/pdf", 1000))
                    .isInstanceOf(IllegalStateException.class).hasMessage("第三方解析服务未配置");
            verifyNoInteractions(input);
            var health = application.getBean("documentParserHealthIndicator", HealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(health.getDetails()).containsEntry("reason", "EXTERNAL_PARSER_UNAVAILABLE");
        });
    }

    @Test
    void externalAdapterReplacesDefaultAndControlsAvailability() {
        DocumentParser adapter = mock(DocumentParser.class);
        when(adapter.isAvailable()).thenReturn(true);
        context.withBean(DocumentParser.class, () -> adapter).run(application -> {
            assertThat(application).hasSingleBean(DocumentParser.class);
            assertThat(application.getBean(DocumentParser.class)).isSameAs(adapter);
            var indicator = application.getBean("documentParserHealthIndicator", HealthIndicator.class);
            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
            when(adapter.isAvailable()).thenReturn(false);
            assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(application.getBean(DocumentParser.class)).isSameAs(adapter);
        });
    }

    @Test
    void unavailableParserStopsPipelineBeforeStorageAndEmbedding() {
        ObjectStorage storage = mock(ObjectStorage.class);
        var clients = mock(EmbeddingClientRegistry.class);
        var pipeline = new DefaultParsePipeline(storage, new UnavailableDocumentParser(),
                new IngestionProperties(1024, 1000, 100, 0), clients);

        assertThatThrownBy(() -> pipeline.process(ParseEmbeddingFixture.task(), () -> true))
                .isInstanceOf(IllegalStateException.class).hasMessage("第三方解析服务未配置或不可用");
        verifyNoInteractions(storage, clients);
    }
}
