package com.zhishiku.kb.ai.chat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalPropertiesTest {
    @Test
    void defaultsAndOverridesAreIndependentOfModelTimeout() {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of()));
        assertThat(binder.bindOrCreate("kb.ai.retrieval", Bindable.of(RetrievalProperties.class)))
                .isEqualTo(RetrievalProperties.defaults());
        assertThat(new AiProperties("http://unused.invalid", 100, 15000, 60).upstreamTimeout()).isEqualTo(60);
        for (String key : new String[]{"timeout-ms", "max-response-bytes"}) {
            for (String invalid : new String[]{"0", "-1", Long.toString(Long.MAX_VALUE)}) {
                var broken = new Binder(new MapConfigurationPropertySource(Map.of("kb.ai.retrieval." + key, invalid)));
                assertThatThrownBy(() -> broken.bindOrCreate("kb.ai.retrieval", Bindable.of(RetrievalProperties.class)))
                        .isInstanceOf(RuntimeException.class);
            }
        }
        for (int topK : new int[]{0, -1, 201, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> new AiProperties("http://unused.invalid", topK, 15000, 60))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
