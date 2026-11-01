package com.zhishiku.kb.ai.model;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class ChatModelLoggingTest {
    @Test
    void transportExceptionCannotExposeCredentialsBodyOrOriginalCause() {
        String secret = "private-canary-password-document";
        var logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var client = WebClient.builder().exchangeFunction(request -> Mono.error(new IllegalStateException(secret))).build();
            var transport = new OpenAiChatTransport(ChatModelPropertiesTest.configured(), 60,
                    client, OpenAiChatTransportTest.decoder());
            StepVerifier.create(transport.stream(OpenAiChatTransportTest.request())).expectErrorSatisfies(error -> {
                assertThat(error).hasNoCause();
                var writer = new StringWriter();
                error.printStackTrace(new PrintWriter(writer));
                assertThat(writer.toString()).doesNotContain(secret, "secret-canary", "https://model.invalid");
            }).verify();
            for (var event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(secret, "secret-canary");
                assertThat(event.getThrowableProxy()).isNull();
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
