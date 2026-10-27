package com.zhishiku.kb.app.comment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 只按实际配置文件绑定 kb.comment，不启动应用上下文；隔离系统环境变量，结果不受本机配置影响。 */
class CommentPropertiesTest {
    @Test
    void creationIsEnabledByDefaultInEveryProfile() throws IOException {
        for (String profile : List.of("prod", "dev")) {
            assertThat(bind(profile, Map.of()).isCreationEnabled()).as(profile).isTrue();
        }
    }

    @Test
    void environmentVariableSwitchesCreationOffAndOnAgain() throws IOException {
        for (String profile : List.of("prod", "dev")) {
            assertThat(bind(profile, Map.of("KB_COMMENT_CREATION_ENABLED", "false")).isCreationEnabled()).as(profile).isFalse();
            assertThat(bind(profile, Map.of("KB_COMMENT_CREATION_ENABLED", "true")).isCreationEnabled()).as(profile).isTrue();
        }
    }

    private static CommentProperties bind(String profile, Map<String, String> variables) throws IOException {
        var environment = new StandardEnvironment();
        var sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        var loader = new YamlPropertySourceLoader();
        for (var source : loader.load("base", new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        if (profile.equals("dev")) {
            for (var source : loader.load("dev", new ClassPathResource("application-dev.yml"))) {
                sources.addFirst(source);
            }
        }
        sources.addFirst(new MapPropertySource("test", new HashMap<>(variables)));
        return Binder.get(environment).bindOrCreate("kb.comment", CommentProperties.class);
    }
}
