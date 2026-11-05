package com.zhishiku.kb.worker.parse;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParserConfiguredMetricsTest {
    @Test
    void unavailableAdapterExportsExplicitZero() {
        new ApplicationContextRunner().withUserConfiguration(DocumentParserConfiguration.class).run(context -> {
            var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            try {
                context.getBean("documentParserConfigured", io.micrometer.core.instrument.binder.MeterBinder.class).bindTo(registry);
                assertThat(registry.scrape()).contains("kb_parse_parser_configured 0.0");
                assertThat(context.getBean(DocumentParser.class)).isInstanceOf(UnavailableDocumentParser.class);
            } finally {
                registry.close();
            }
        });
    }

    @Test
    void configuredAdapterNeverCallsRemoteParsingEvenWhenItWouldFail() throws Exception {
        var parser = mock(DocumentParser.class);
        when(parser.isAvailable()).thenReturn(true);
        when(parser.parse(any(InputStream.class), anyString(), anyString(), anyInt())).thenThrow(new IOException("offline"));
        new ApplicationContextRunner().withUserConfiguration(DocumentParserConfiguration.class)
                .withBean(DocumentParser.class, () -> parser).run(context -> {
                    var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
                    try {
                        context.getBean("documentParserConfigured", io.micrometer.core.instrument.binder.MeterBinder.class).bindTo(registry);
                        assertThat(registry.scrape()).contains("kb_parse_parser_configured 1.0");
                        assertThat(registry.scrape()).contains("kb_parse_parser_configured 1.0");
                    } finally {
                        registry.close();
                    }
                });
        verify(parser, never()).parse(any(), any(), any(), anyInt());
        verify(parser, never()).parseDocument(any(), any(), any(), anyInt());
    }
}
