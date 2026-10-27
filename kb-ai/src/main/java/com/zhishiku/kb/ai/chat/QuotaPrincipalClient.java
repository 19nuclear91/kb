package com.zhishiku.kb.ai.chat;

import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.AuthHeaders;
import com.zhishiku.kb.common.security.QuotaPrincipal;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 从 kb-app 根据可信会话解析用户所属部门；AI 服务不直连业务库。
 */
@Component
public class QuotaPrincipalClient {

    private static final ParameterizedTypeReference<R<QuotaPrincipal>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;
    private final AiProperties props;

    public QuotaPrincipalClient(WebClient.Builder builder, AiProperties props) {
        this.props = props;
        this.webClient = builder.baseUrl(props.appBaseUrl()).build();
    }

    public Mono<QuotaPrincipal> resolve(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            return Mono.error(new QuotaUnavailableException("缺少有效会话，无法解析部门配额"));
        }
        return webClient.get()
                .uri("/api/auth/quota-context")
                .header(AuthHeaders.SA_TOKEN, tokenValue)
                .retrieve()
                .onStatus(status -> status.value() == 401 || status.value() == 403,
                        response -> Mono.error(new QuotaPrincipalRejectedException(
                                "当前用户或部门已停用，无法使用 AI 配额")))
                .bodyToMono(RESPONSE_TYPE)
                .timeout(Duration.ofSeconds(Math.max(1, props.upstreamTimeout())))
                .flatMap(response -> {
                    if (response == null || !response.isOk() || response.data() == null) {
                        return Mono.error(new QuotaUnavailableException("无法解析当前用户的部门配额"));
                    }
                    return Mono.just(response.data());
                })
                .onErrorMap(error -> error instanceof QuotaUnavailableException
                        ? error : new QuotaUnavailableException("部门配额服务不可用", error));
    }

}
