package com.zhishiku.kb.ai.evaluation;

import com.zhishiku.kb.answereval.Checks;
import com.zhishiku.kb.answereval.LocalFiles;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 显式测试采集配置；关闭时不解析或访问采集目录。 */
public record EvaluationProperties(boolean enabled, String instanceId, Set<Long> allowedUsers, Path root) {
    public static EvaluationProperties read(Environment environment) {
        try {
            if (!environment.getProperty("kb.ai.evaluation.enabled", Boolean.class, false)) {
                return new EvaluationProperties(false, null, Set.of(), null);
            }
            if (Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equalsIgnoreCase)
                    || Arrays.stream(environment.getDefaultProfiles()).anyMatch("prod"::equalsIgnoreCase)) {
                throw invalid();
            }
            String instance = Checks.id(environment.getRequiredProperty("kb.ai.evaluation.instance-id"));
            Set<Long> users = Arrays.stream(environment.getRequiredProperty("kb.ai.evaluation.allowed-users").split(","))
                    .map(String::strip).map(Long::parseLong).collect(Collectors.toUnmodifiableSet());
            if (users.isEmpty() || users.stream().anyMatch(user -> user <= 0)) {
                throw invalid();
            }
            Path root = LocalFiles.path(environment.getRequiredProperty("kb.ai.evaluation.directory"));
            return new EvaluationProperties(true, instance, users, root);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    public static IllegalArgumentException invalid() {
        return new IllegalArgumentException("评测采集配置无效");
    }

    @Override
    public String toString() {
        return "EvaluationProperties[enabled=" + enabled + "]";
    }
}
