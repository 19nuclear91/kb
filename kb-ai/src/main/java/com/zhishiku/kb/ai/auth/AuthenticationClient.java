package com.zhishiku.kb.ai.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.AuthHeaders;
import com.zhishiku.kb.common.security.AuthenticationCheck;
import com.zhishiku.kb.common.security.AuthenticationSignature;
import com.zhishiku.kb.common.security.IdentityException;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** 独立客户端只发送目标 token 与签名；无跨请求缓存、自动重试或数据库访问。 */
@Component
public class AuthenticationClient {
    private final WebClient client;
    private final String secret;

    @Autowired
    public AuthenticationClient(@Value("${kb.auth.internal.app-base-url:}") String appBaseUrl,
                                @Value("${kb.auth.internal.secret:}") String secret) {
        this(createClient(appBaseUrl), secret);
    }

    public AuthenticationClient(WebClient client, String secret) {
        this.client = client;
        this.secret = secret;
    }

    public Mono<AuthenticationCheck> check(String token) {
        return Mono.defer(() -> {
            if (token == null || token.isBlank() || token.length() > 4096) {
                return Mono.error(IdentityException.unauthorized("登录状态无效或已过期"));
            }
            if (client == null || !AuthenticationSignature.configured(secret)) {
                return Mono.error(unavailable());
            }
            var signed = AuthenticationSignature.sign(secret, "kb-ai", token, Clock.systemUTC());
            return client.post().uri(AuthenticationSignature.PATH).headers(headers -> {
                headers.set(AuthenticationSignature.CALLER, signed.caller());
                headers.set(AuthenticationSignature.TIMESTAMP, Long.toString(signed.timestamp()));
                headers.set(AuthenticationSignature.NONCE, signed.nonce());
                headers.set(AuthenticationSignature.SIGNATURE, signed.signature());
                headers.set(AuthHeaders.SA_TOKEN, token);
            }).exchangeToMono(response -> {
                int status = response.statusCode().value();
                if (status != 200) {
                    ApiException error = status == 401 ? IdentityException.unauthorized("登录状态无效或已过期")
                            : status == 403 ? IdentityException.forbidden("身份访问被拒绝") : unavailable();
                    return response.releaseBody().then(Mono.<AuthenticationCheck>error(error));
                }
                return response.bodyToMono(JsonNode.class).switchIfEmpty(Mono.error(unavailable()))
                        .map(AuthenticationClient::decode);
            });
        }).timeout(Duration.ofSeconds(2))
                .onErrorMap(error -> error instanceof ApiException known ? known : unavailable());
    }

    public Mono<Void> reportStop(byte[] body) {
        return Mono.defer(() -> {
            if (client == null || !AuthenticationSignature.configured(secret)) {
                return Mono.error(unavailable());
            }
            var signed = AuthenticationSignature.signStop(secret, body, Clock.systemUTC());
            return client.post().uri(AuthenticationSignature.STOP_PATH).headers(headers -> {
                headers.set(AuthenticationSignature.CALLER, signed.caller());
                headers.set(AuthenticationSignature.TIMESTAMP, Long.toString(signed.timestamp()));
                headers.set(AuthenticationSignature.NONCE, signed.nonce());
                headers.set(AuthenticationSignature.SIGNATURE, signed.signature());
            }).contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(body)
                    .exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        if (status == 204) {
                            return response.releaseBody();
                        }
                        ApiException error = status == 400 ? ApiException.badRequest("停止证据被拒绝")
                                : status == 403 ? ApiException.forbidden("停止证据被拒绝")
                                : status == 409 ? ApiException.conflict("停止证据绑定冲突") : unavailable();
                        return response.releaseBody().then(Mono.<Void>error(error));
                    });
        }).timeout(Duration.ofSeconds(2))
                .onErrorMap(error -> error instanceof ApiException known ? known : unavailable());
    }

    private static AuthenticationCheck decode(JsonNode value) {
        if (!value.isObject() || !value.path("userId").isIntegralNumber()
                || !value.path("userId").canConvertToLong() || !value.path("authEpoch").isIntegralNumber()
                || !value.path("authEpoch").canConvertToLong() || !value.path("mustChangePassword").isBoolean()
                || !value.path("checkedAt").isTextual()) {
            throw unavailable();
        }
        return new AuthenticationCheck(value.path("userId").longValue(), value.path("authEpoch").longValue(),
                value.path("mustChangePassword").booleanValue(), Instant.parse(value.path("checkedAt").textValue()));
    }

    private static WebClient createClient(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        URI uri = URI.create(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("内部认证地址必须为固定服务源站");
        }
        return WebClient.builder().baseUrl(uri.toString())
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().followRedirect(false)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000).responseTimeout(Duration.ofSeconds(2))))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4096)).build();
    }

    private static ApiException unavailable() {
        return ApiException.unavailable("暂时无法确认身份，请稍后重试");
    }
}
