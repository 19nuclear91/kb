package com.zhishiku.kb.ai.evaluation;

import com.zhishiku.kb.ai.model.ModelEvent;
import com.zhishiku.kb.answereval.DataModel;
import com.zhishiku.kb.answereval.OutputPublisher;
import com.zhishiku.kb.answereval.StrictJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CaptureSessionTest {
    @TempDir
    Path root;

    @Test
    void exactFinalMessagesAndUnicodeDigestAreSeparateFromBusinessCompletion() throws IOException {
        var registration = CaptureFixtures.registration(root, 9);
        try (var registry = registry(new OutputPublisher())) {
            var session = registry.claim(CaptureFixtures.begin(9), CaptureFixtures.QUESTION);
            var materials = List.of(CaptureFixtures.citation("报销上限", true));
            var request = CaptureFixtures.request(materials);
            session.sealed(false, materials, true);
            session.input(request, materials, request.encode());
            session.subscribed();
            session.event(new ModelEvent.Delta("答案🧾"));
            session.event(new ModelEvent.FinalUsage(10, 3));
            session.modelCompleted();
            session.businessSaved("COMPLETED");
            session.finish();
            var snapshot = CaptureFixtures.snapshot(root, registration);
            assertThat(snapshot.requestSha256()).isEqualTo(StrictJson.sha(request.encode()));
            assertThat(snapshot.materials().getFirst().text()).isEqualTo("报销上限");
            assertThat(snapshot.answerSha256()).isEqualTo(StrictJson.textSha("答案🧾"));
            assertThat(snapshot.answerCodePoints()).isEqualTo(3);
            assertThat(snapshot.modelStage()).isEqualTo(DataModel.ModelStage.MODEL_COMPLETED);
            assertThat(snapshot.businessStage()).isEqualTo(DataModel.BusinessStage.COMPLETED);
            assertThat(snapshot.settlementStage()).isEqualTo(DataModel.SettlementStage.PENDING);
            assertThat(new String(session.payload(), java.nio.charset.StandardCharsets.UTF_8))
                    .doesNotContain("INTERNAL_TICKET_SENTINEL");
        }
    }

    @Test
    void outsideCorpusProducesOnlySafeFailureAndNoContent() throws IOException {
        var registration = CaptureFixtures.registration(root, 9);
        try (var registry = registry(new OutputPublisher())) {
            var session = registry.claim(CaptureFixtures.begin(9), CaptureFixtures.QUESTION);
            var materials = List.of(CaptureFixtures.citation("OUTSIDE_PRIVATE_TEXT", false));
            var request = CaptureFixtures.request(materials);
            assertThatCode(() -> session.input(request, materials, request.encode())).doesNotThrowAnyException();
            session.subscribed();
            session.event(new ModelEvent.Delta("PRIVATE_ANSWER"));
            session.modelCompleted();
            session.finish();
            Path result = root.resolve("captures").resolve(registration.artifactKey());
            assertThat(result.resolve("input.json")).doesNotExist();
            assertThat(Files.readString(result.resolve("failure.json")))
                    .doesNotContain("OUTSIDE_PRIVATE_TEXT", "PRIVATE_ANSWER", CaptureFixtures.QUESTION);
        }
    }

    @Test
    void noResultsDoesNotInventModelInputOrUsage() throws IOException {
        var registration = CaptureFixtures.registration(root, 9);
        try (var registry = registry(new OutputPublisher())) {
            var session = registry.claim(CaptureFixtures.begin(9), CaptureFixtures.QUESTION);
            session.sealed(true, List.of(), true);
            session.businessSaved("COMPLETED");
            session.settled();
            session.finish();
            var snapshot = CaptureFixtures.snapshot(root, registration);
            assertThat(snapshot.modelStage()).isEqualTo(DataModel.ModelStage.NOT_CALLED);
            assertThat(snapshot.messages()).isEmpty();
            assertThat(snapshot.answerSha256()).isNull();
            assertThat(snapshot.usage()).isEqualTo(new DataModel.Usage(null, null));
            assertThat(snapshot.settlementStage()).isEqualTo(DataModel.SettlementStage.CONFIRMED);
        }
    }

    @Test
    void failureCancelAndUnsupportedModelsNeverBecomeCompleted() throws IOException {
        for (int mode = 0; mode < 3; mode++) {
            Path run = Files.createDirectory(root.resolve("mode-" + mode));
            var registration = CaptureFixtures.registration(run, 9);
            try (var registry = new CaptureRegistry(CaptureFixtures.properties(run), CaptureFixtures.CLOCK,
                    new OutputPublisher(), Runnable::run)) {
                var session = registry.claim(CaptureFixtures.begin(9), CaptureFixtures.QUESTION);
                if (mode == 2) {
                    session.sealed(false, List.of(CaptureFixtures.citation(CaptureFixtures.TEXT, false)), false);
                } else {
                    var materials = List.of(CaptureFixtures.citation(CaptureFixtures.TEXT, false));
                    var request = CaptureFixtures.request(materials);
                    session.input(request, materials, request.encode());
                    session.subscribed();
                    session.event(new ModelEvent.Delta("partial"));
                    session.modelFailed(mode == 1);
                }
                session.finish();
                session.modelCompleted();
                var snapshot = CaptureFixtures.snapshot(run, registration);
                assertThat(snapshot.modelStage()).isEqualTo(mode == 0 ? DataModel.ModelStage.MODEL_FAILED
                        : mode == 1 ? DataModel.ModelStage.CANCELLED : DataModel.ModelStage.UNSUPPORTED);
                assertThat(snapshot.businessStage()).isEqualTo(DataModel.BusinessStage.NOT_CONFIRMED);
                assertThat(snapshot.answerSha256()).isNull();
            }
        }
    }

    @Test
    void diskFailureAndFullQueueDoNotEscapeIntoBusinessOrPublishPartialData() throws IOException {
        var registration = CaptureFixtures.registration(root, 9);
        OutputPublisher broken = new OutputPublisher() {
            @Override
            protected void write(OutputStream output, byte[] bytes) throws IOException {
                throw new IOException("secret");
            }
        };
        try (var registry = registry(broken)) {
            var session = registry.claim(CaptureFixtures.begin(9), CaptureFixtures.QUESTION);
            assertThatCode(session::finish).doesNotThrowAnyException();
            assertThat(root.resolve("captures").resolve(registration.artifactKey())).doesNotExist();
        }
        var next = CaptureFixtures.registration(root, 10);
        try (var registry = new CaptureRegistry(CaptureFixtures.properties(root), CaptureFixtures.CLOCK, new OutputPublisher(),
                command -> {
                    throw new RejectedExecutionException("queue full");
                })) {
            var session = registry.claim(CaptureFixtures.begin(10), CaptureFixtures.QUESTION);
            assertThatCode(session::finish).doesNotThrowAnyException();
            assertThat(root.resolve("captures").resolve(next.artifactKey())).doesNotExist();
        }
    }

    private CaptureRegistry registry(OutputPublisher publisher) {
        return new CaptureRegistry(CaptureFixtures.properties(root), CaptureFixtures.CLOCK, publisher, Runnable::run);
    }

    @Test
    void boundedQueueCapacityAndLateCallbacksCannotProduceFalseSuccess() throws IOException {
        List<Runnable> queued = new ArrayList<>();
        List<DataModel.Registration> registrations = new ArrayList<>();
        try (var registry = new CaptureRegistry(CaptureFixtures.properties(root), CaptureFixtures.CLOCK,
                new OutputPublisher(), queued::add)) {
            for (long conversation = 1; conversation <= 5; conversation++) {
                var registration = CaptureFixtures.registration(root, conversation);
                registrations.add(registration);
                var session = registry.claim(CaptureFixtures.begin(conversation), CaptureFixtures.QUESTION);
                session.finish();
                session.businessSaved("COMPLETED");
                session.settled();
            }
            assertThat(queued).hasSize(4);
            queued.forEach(Runnable::run);
            assertThat(root.resolve("captures").resolve(registrations.getLast().artifactKey())).doesNotExist();
            assertThat(CaptureFixtures.snapshot(root, registrations.getFirst()).businessStage())
                    .isEqualTo(DataModel.BusinessStage.NOT_CONFIRMED);
        }
        var limited = CaptureFixtures.registration(root, 6);
        try (var registry = new CaptureRegistry(CaptureFixtures.properties(root), CaptureFixtures.CLOCK,
                new OutputPublisher(), Runnable::run, 1)) {
            registry.claim(CaptureFixtures.begin(6), CaptureFixtures.QUESTION).finish();
            assertThat(root.resolve("captures").resolve(limited.artifactKey())).doesNotExist();
        }
    }
}
