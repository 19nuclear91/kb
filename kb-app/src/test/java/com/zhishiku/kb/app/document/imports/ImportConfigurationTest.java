package com.zhishiku.kb.app.document.imports;

import com.zhishiku.kb.app.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 实际 YAML 和 Boot 配置绑定，无应用上下文及外部连接。 */
class ImportConfigurationTest {
    @Test
    void productionTemplateKeepsCapabilitiesAndMultipartAligned() throws Exception {
        var binder = binder(Map.of());
        var storage = binder.bind("kb.storage", StorageProperties.class).get();
        var multipart = binder.bind("spring.servlet.multipart", MultipartProperties.class).get();
        var imports = binder.bind("kb.document-import", ImportProperties.class).get();
        new ImportConfiguration(storage, multipart);
        assertThat(imports.capabilities(storage).maxFileBytes()).isEqualTo(multipart.getMaxFileSize().toBytes());
        assertThat(multipart.getMaxRequestSize().toBytes()).isEqualTo(51L * 1024 * 1024);
        assertThat(imports.storageSeconds()).isLessThanOrEqualTo(imports.attemptSeconds());
        assertThat(imports.attemptSeconds()).isLessThan(150);
    }

    @Test
    void mismatchedAndUnboundedMultipartConfigurationIsRejected() throws Exception {
        for (Map<String, Object> values : List.of(Map.<String, Object>of("KB_MAX_UPLOAD_SIZE", "60MB"),
                Map.<String, Object>of("KB_MAX_UPLOAD_REQUEST_SIZE", "50MB"),
                Map.<String, Object>of("KB_MAX_UPLOAD_REQUEST_SIZE", "100MB"),
                Map.<String, Object>of("spring.servlet.multipart.file-size-threshold", "1MB"))) {
            var binder = binder(values);
            assertThatThrownBy(() -> new ImportConfiguration(binder.bind("kb.storage", StorageProperties.class).get(),
                    binder.bind("spring.servlet.multipart", MultipartProperties.class).get())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private Binder binder(Map<String, Object> values) throws Exception {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", values));
        for (var source : new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment);
    }
}
