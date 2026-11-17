package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.budget.BudgetCapability;
import com.zhishiku.kb.ai.budget.BudgetKind;
import com.zhishiku.kb.ai.budget.InputTokenCount;
import com.zhishiku.kb.ai.conversation.ConversationBudget;
import com.zhishiku.kb.ai.conversation.MultiTurnModel;
import com.zhishiku.kb.ai.evaluation.CaptureFixtures;
import com.zhishiku.kb.ai.evaluation.CaptureSession;
import com.zhishiku.kb.answereval.DataModel;
import com.zhishiku.kb.answereval.StrictJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelEvaluationCaptureTest {
    @TempDir
    Path root;

    @Test
    void repeatedBudgetViewsDoNotReplaceActualEncodedRequestOrAddSubscriptions() throws IOException {
        var bound = CaptureFixtures.bound(root);
        var capture = bound.session();
        var materials = List.of(CaptureFixtures.citation("报销上限", true));
        capture.sealed(false, materials, true);
        var properties = ChatModelPropertiesTest.configured();
        var renderer = new ChatPromptRenderer(properties);
        var calls = new AtomicInteger();
        var client = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.just(OpenAiChatTransportTest.response(200, "text/event-stream", Flux.empty()));
        }).build();
        var transport = new OpenAiChatTransport(properties, 60, client, OpenAiChatTransportTest.decoder());
        var budget = mock(ChatBudgetPolicy.class);
        when(budget.capability()).thenReturn(new BudgetCapability(BudgetKind.VERIFIED, 16000, 0));
        when(budget.count(any())).thenReturn(new InputTokenCount(BudgetKind.VERIFIED, 100));
        var model = new OpenAiMultiTurnModel(renderer, transport, budget, 12000);
        var input = new MultiTurnModel.Input(ConversationBudget.INSTRUCTION,
                List.of(new MultiTurnModel.Message("user", CaptureFixtures.QUESTION)), materials, 100);
        model.countInputTokens(input);
        model.countInputTokens(new MultiTurnModel.Input(ConversationBudget.INSTRUCTION, input.messages(),
                List.of(CaptureFixtures.citation(CaptureFixtures.TEXT, false)), 100));
        assertThat(root.resolve("captures")).doesNotExist();
        StepVerifier.create(model.generate(input).contextWrite(context -> context.put(CaptureSession.class, capture)))
                .expectNextCount(2).verifyComplete();
        capture.finish();
        var snapshot = CaptureFixtures.snapshot(root, bound.registration());
        assertThat(calls).hasValue(1);
        assertThat(snapshot.requestSha256()).isEqualTo(StrictJson.sha(renderer.render(input).encode()));
        assertThat(snapshot.materials().getFirst().text()).isEqualTo("报销上限");
        assertThat(snapshot.messages().stream().map(DataModel.Message::content))
                .doesNotContain(CaptureFixtures.TEXT);
        assertThat(snapshot.modelStage()).isEqualTo(DataModel.ModelStage.MODEL_COMPLETED);
        assertThat(snapshot.businessStage()).isEqualTo(DataModel.BusinessStage.NOT_CONFIRMED);
    }

    @Test
    void preparedInputAndTransportFailureDoNotBecomeSuccessfulModelExecution() throws IOException {
        var bound = CaptureFixtures.bound(root);
        var materials = List.of(CaptureFixtures.citation(CaptureFixtures.TEXT, false));
        var request = CaptureFixtures.request(materials);
        bound.session().sealed(false, materials, true);
        var client = WebClient.builder().exchangeFunction(ignored -> Mono.error(new IllegalStateException("SECRET_BODY"))).build();
        var transport = new OpenAiChatTransport(ChatModelPropertiesTest.configured(), 60, client, OpenAiChatTransportTest.decoder());
        StepVerifier.create(transport.stream(request).contextWrite(context -> context
                        .put(CaptureSession.class, bound.session()).put(CaptureSession.MATERIALS_KEY, materials)))
                .expectError(ChatModelFailure.class).verify();
        bound.session().finish();
        var snapshot = CaptureFixtures.snapshot(root, bound.registration());
        assertThat(snapshot.requestSha256()).isEqualTo(StrictJson.sha(request.encode()));
        assertThat(snapshot.modelStage()).isEqualTo(DataModel.ModelStage.MODEL_FAILED);
        assertThat(snapshot.answerSha256()).isNull();
        assertThat(new String(StrictJson.bytes(snapshot), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("SECRET_BODY");
    }
}
