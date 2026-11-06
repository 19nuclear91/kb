package com.zhishiku.kb.ai.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.ai.chat.AiProperties;
import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import com.zhishiku.kb.common.security.AccountRevokedException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationRevocationProtocolTest {
    @ParameterizedTest
    @CsvSource({"401,confirmed", "403,confirmed", "401,absent", "403,absent", "403,unknown", "403,duplicate", "409,confirmed"})
    void onlyExplicitTrustedUserDenialMapsToRevocationAndNeverRetries(int status, String marker) {
        var calls = new AtomicInteger();
        var properties = new ConversationProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef");
        var builder = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            var response = ClientResponse.create(HttpStatus.valueOf(status));
            if (!"absent".equals(marker)) {
                response.header(AccountRevokedException.HEADER,
                        "unknown".equals(marker) ? "OTHER" : AccountRevokedException.CODE);
            }
            if ("duplicate".equals(marker)) {
                response.header(AccountRevokedException.HEADER, AccountRevokedException.CODE);
            }
            return Mono.just(response.body("private error details").build());
        });
        var client = new ConversationAppClient(builder, new AiProperties("http://app.invalid", 5, 10000, 60),
                properties, new ObjectMapper());
        StepVerifier.create(client.call("dispatch", Map.of(), null, true)).expectErrorSatisfies(error -> {
            if ((status == 401 || status == 403) && "confirmed".equals(marker)) {
                assertThat(error).isInstanceOf(AccountRevokedException.class);
            } else {
                assertThat(error).isInstanceOf(ResponseStatusException.class).isNotInstanceOf(AccountRevokedException.class);
            }
            assertThat(error.getMessage()).doesNotContain("private");
        }).verify();
        assertThat(calls).hasValue(1);
    }
}
