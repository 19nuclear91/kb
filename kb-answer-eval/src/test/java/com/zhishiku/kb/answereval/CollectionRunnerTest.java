package com.zhishiku.kb.answereval;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionRunnerTest {
    @TempDir
    Path root;

    @Test
    void runsIndependentFirstTurnsPreservesQuestionAndNeverSendsLabels() throws IOException {
        var batch = batch(2);
        var backend = new Backend(batch, "wrong-answer");
        assertThat(run(batch, backend)).isZero();
        assertThat(backend.creates).isEqualTo(2);
        assertThat(backend.generates).isEqualTo(2);
        assertThat(backend.requests).extracting(node -> node.get("requestId").textValue()).doesNotHaveDuplicates();
        assertThat(backend.requests).allSatisfy(node -> {
            assertThat(node.fieldNames()).toIterable().containsExactlyInAnyOrder("question", "requestId", "expectedLastTurnSeq");
            assertThat(node.path("expectedLastTurnSeq").longValue()).isZero();
            assertThat(node.path("question").textValue()).isEqualTo(DataFixtures.item().question());
        });
        var collection = collection();
        assertThat(collection.complete()).isTrue();
        assertThat(collection.caseIds()).hasSize(2);
        assertThat(collection.corpusState()).isEqualTo(DataModel.ClaimState.DECLARED_ONLY);
        assertThat(collection.invocationOrigin()).isEqualTo(DataModel.InvocationOrigin.TEST_DOUBLE);
        assertThat(collection.model()).isEqualTo("model");
        var first = answers().getFirst();
        assertThat(first.answer()).isEqualTo("wrong-answer");
        var captured = StrictJson.read(new BundleFiles(root.resolve("result")).read(
                collection.inputs().get(first.caseId()), OutputPublisher.CAPTURE_LIMIT).bytes(), DataModel.InputSnapshot.class);
        assertThat(captured.model()).isEqualTo("observed-model");
    }

    @Test
    void unknownSubmissionStopsBatchWithoutRepeatingGenerationOrDroppingDenominator() throws IOException {
        var batch = batch(3);
        var backend = new Backend(batch, "disconnect");
        assertThat(run(batch, backend)).isEqualTo(3);
        assertThat(backend.creates).isEqualTo(1);
        assertThat(backend.generates).isEqualTo(1);
        assertThat(answers()).extracting(DataModel.Answer::status).containsExactly(
                DataModel.ExecutionStatus.UNKNOWN, DataModel.ExecutionStatus.NOT_RUN, DataModel.ExecutionStatus.NOT_RUN);
        assertThat(answers().getFirst().error()).isEqualTo(EvaluationFailure.Code.SUBMISSION_UNKNOWN);
        assertThat(answers().getFirst().attempt().conversationId()).isPositive();
        assertThat(answers()).allSatisfy(answer -> assertThat(answer.answer()).isNull());
        assertThat(collection().complete()).isFalse();
    }

    @Test
    void mismatchBlockedUnsettledMissingCaptureAndReplayCannotPublishContent() throws IOException {
        for (String failure : List.of("mismatch", "unapproved", "blocked", "pending", "missing", "replay", "source-mismatch", "model-mismatch")) {
            Path runRoot = Files.createDirectory(root.resolve(failure));
            var batch = new DatasetReader().read(DataFixtures.dataset(runRoot.resolve("data")).toString());
            var backend = new Backend(batch, failure);
            backend.shared = runRoot.resolve("shared");
            assertThat(runAt(batch, backend, runRoot)).isEqualTo(3);
            var bundle = new BundleFiles(runRoot.resolve("result"));
            var manifest = StrictJson.read(bundle.read("collection.json", StrictJson.MANIFEST_LIMIT).bytes(), DataModel.Collection.class);
            var answer = StrictJson.lines(bundle.read(manifest.answers(), StrictJson.FILE_LIMIT).bytes(), DataModel.Answer.class, 1000).getFirst();
            assertThat(answer.answer()).isNull();
            assertThat(manifest.inputs()).isEmpty();
            assertThat(manifest.complete()).isFalse();
        }
    }

    @Test
    void genuineNoResultsIsCompleteButExecutionErrorsAreNotAbstentions() throws IOException {
        for (String mode : List.of("no-results", "budget-error", "search-error")) {
            Path runRoot = Files.createDirectory(root.resolve(mode));
            var batch = new DatasetReader().read(DataFixtures.dataset(runRoot.resolve("data")).toString());
            var backend = new Backend(batch, mode);
            backend.shared = runRoot.resolve("shared");
            assertThat(runAt(batch, backend, runRoot)).isEqualTo("no-results".equals(mode) ? 0 : 3);
        }
    }

    @Test
    void lostCreationResponseKeepsItsRequestIdentityAndNeverStartsGeneration() throws IOException {
        var batch = batch(2);
        var backend = new Backend(batch, "create-disconnect");
        assertThat(run(batch, backend)).isEqualTo(3);
        var answer = answers().getFirst();
        assertThat(answer.status()).isEqualTo(DataModel.ExecutionStatus.UNKNOWN);
        assertThat(answer.attempt().createRequestId()).isEqualTo(backend.createRequestId);
        assertThat(answer.attempt().conversationId()).isNull();
        assertThat(backend.creates).isEqualTo(1);
        assertThat(backend.generates).isZero();
        assertThat(answers().getLast().status()).isEqualTo(DataModel.ExecutionStatus.NOT_RUN);
    }

    @Test
    void incompleteResponseKeepsConfirmedTurnWithoutRetryOrPublishingBody() throws IOException {
        for (String mode : List.of("eof", "body-io")) {
            Path runRoot = Files.createDirectory(root.resolve(mode));
            var batch = new DatasetReader().read(DataFixtures.dataset(runRoot.resolve("data")).toString());
            var backend = new Backend(batch, mode);
            backend.shared = runRoot.resolve("shared");
            assertThat(runAt(batch, backend, runRoot)).isEqualTo(3);
            var result = new CollectionReader().read(runRoot.resolve("result/collection.json").toString(), batch);
            var answer = result.answers().get("case-1");
            assertThat(answer.status()).isEqualTo(DataModel.ExecutionStatus.UNKNOWN);
            assertThat(answer.attempt().turnId()).isEqualTo(backend.saved.get("id"));
            assertThat(answer.answer()).isNull();
            assertThat(result.inputs()).isEmpty();
            assertThat(backend.generates).isEqualTo(1);
        }
    }

    @Test
    void incompleteCollectionRetainsConfirmedBusinessSettlementAndUsage() throws IOException {
        for (String mode : List.of("pending", "missing", "model-mismatch")) {
            Path runRoot = Files.createDirectory(root.resolve(mode));
            var batch = new DatasetReader().read(DataFixtures.dataset(runRoot.resolve("data")).toString());
            var backend = new Backend(batch, mode);
            backend.shared = runRoot.resolve("shared");
            assertThat(runAt(batch, backend, runRoot)).isEqualTo(3);
            var result = new CollectionReader().read(runRoot.resolve("result/collection.json").toString(), batch);
            var answer = result.answers().get("case-1");
            assertThat(answer.businessStage()).isEqualTo(DataModel.BusinessStage.COMPLETED);
            assertThat(answer.settlementStage()).isEqualTo("pending".equals(mode)
                    ? DataModel.SettlementStage.PENDING : DataModel.SettlementStage.CONFIRMED);
            assertThat(answer.attempt().turnId()).isEqualTo(backend.saved.get("id"));
            assertThat(answer.usage()).isEqualTo(new DataModel.Usage(10L, 3L));
            assertThat(answer.answer()).isNull();
            assertThat(answer.sources()).isEmpty();
            assertThat(answer.persistedAnswerSha256()).isNull();
            assertThat(result.inputs()).isEmpty();
            if ("model-mismatch".equals(mode)) {
                assertThat(answer.binding().turnId()).isEqualTo(answer.attempt().turnId());
            }
        }
    }

    @Test
    void caughtInputWriteFailureCannotPublishRunnerBundle() throws IOException {
        var batch = batch(2);
        var backend = new Backend(batch, "wrong-answer");
        var publisher = new OutputPublisher() {
            @Override
            protected void write(OutputStream stream, byte[] bytes) throws IOException {
                if (new String(bytes, StandardCharsets.UTF_8).contains("\"sealedSources\"")) {
                    stream.write(bytes, 0, 3);
                    throw new IOException("SECRET");
                }
                super.write(stream, bytes);
            }
        };
        assertThatThrownBy(() -> runAt(batch, backend, root, publisher)).isInstanceOf(EvaluationFailure.class)
                .hasMessage("ANSWER_EVAL_ERROR OUTPUT");
        assertThat(root.resolve("result")).doesNotExist();
        assertThat(backend.generates).isEqualTo(1);
    }

    private DatasetReader.Batch batch(int count) throws IOException {
        Path manifest = DataFixtures.dataset(root.resolve("data"));
        List<DataModel.Case> cases = new ArrayList<>();
        List<DataModel.Label> labels = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = "case-" + (i + 1);
            cases.add(new DataModel.Case(id, "employee", DataFixtures.item().question(), List.of("amount"), null));
            labels.add(new DataModel.Label(id, DataModel.Expected.ANSWER, DataFixtures.label().facts(),
                    List.of("LABEL_SENTINEL"), null, List.of(), null));
        }
        DatasetReaderTest.replaceFile(manifest, "cases", StrictJson.jsonl(cases));
        DatasetReaderTest.replaceFile(manifest, "labels", StrictJson.jsonl(labels));
        return new DatasetReader().read(manifest.toString());
    }

    private int run(DatasetReader.Batch batch, Backend backend) {
        return runAt(batch, backend, root);
    }

    private int runAt(DatasetReader.Batch batch, Backend backend, Path outputRoot) {
        return runAt(batch, backend, outputRoot, new OutputPublisher());
    }

    private int runAt(DatasetReader.Batch batch, Backend backend, Path outputRoot, OutputPublisher publisher) {
        var config = BusinessClientTest.config(outputRoot);
        AtomicLong time = new AtomicLong();
        var runner = new CollectionRunner(time::get, Clock.systemUTC(), publisher, () -> time.addAndGet(200_000_000_000L));
        return runner.collect(batch, config, config.credentials(batch, name -> "token"), backend,
                publisher.preflight(outputRoot.resolve("result").toString()));
    }

    private DataModel.Collection collection() {
        return StrictJson.read(new BundleFiles(root.resolve("result")).read("collection.json",
                StrictJson.MANIFEST_LIMIT).bytes(), DataModel.Collection.class);
    }

    private List<DataModel.Answer> answers() {
        return StrictJson.lines(new BundleFiles(root.resolve("result")).read(collection().answers(),
                StrictJson.FILE_LIMIT).bytes(), DataModel.Answer.class, 1000);
    }

    private final class Backend implements Transport {
        private final DatasetReader.Batch batch;
        private final String mode;
        private Path shared = root.resolve("shared");
        private int creates;
        private int generates;
        private String createRequestId;
        private final List<JsonNode> requests = new ArrayList<>();
        private final Map<String, Object> saved = new LinkedHashMap<>();

        private Backend(DatasetReader.Batch batch, String mode) {
            this.batch = batch;
            this.mode = mode;
        }

        @Override
        public Response send(Request request, Deadline deadline) throws IOException {
            String path = request.uri().getPath();
            if (path.endsWith("/api/auth/me")) {
                return BusinessClientTest.response(200, Map.of("code", 200, "data", Map.of(
                        "user", Map.of("userId", Long.MAX_VALUE, "enabled", true), "mustChangePassword", false)));
            }
            if (path.endsWith("/api/conversations")) {
                creates++;
                createRequestId = StrictJson.parse(request.body()).path("requestId").textValue();
                if ("create-disconnect".equals(mode)) {
                    throw new IOException("creation response lost");
                }
                Map<String, Object> conversation = new LinkedHashMap<>();
                conversation.put("id", 9000L + creates);
                conversation.put("lastTurnSeq", 0);
                conversation.put("activeTurnId", null);
                return BusinessClientTest.response(201, Map.of("conversation", conversation, "replayed", false));
            }
            if ("GET".equals(request.method())) {
                return BusinessClientTest.response(200, saved);
            }
            generates++;
            if ("disconnect".equals(mode)) {
                throw new IOException("unknown submission");
            }
            if ("budget-error".equals(mode) || "search-error".equals(mode)) {
                return BusinessClientTest.response("budget-error".equals(mode) ? 400 : 503,
                        Map.of("code", "budget-error".equals(mode) ? "INPUT_BUDGET_EXCEEDED" : "SEARCH_UNAVAILABLE"));
            }
            JsonNode question = StrictJson.parse(request.body());
            requests.add(question);
            long conversation = 9000L + creates;
            var registration = StrictJson.read(Files.readAllBytes(shared.resolve("registrations")
                    .resolve(Long.MAX_VALUE + "-" + conversation + ".json")), DataModel.Registration.class);
            String turn = UUID.randomUUID().toString();
            boolean noResults = "no-results".equals(mode);
            String answer = noResults ? CollectionRunner.NO_RESULTS : "wrong-answer".equals(mode) ? "错误金额 500 元" : "100 元🧾";
            if ("wrong-answer".equals(mode)) {
                answer = "wrong-answer";
            }
            DataModel.Source source = "unapproved".equals(mode) ? new DataModel.Source(123, 2, 3) : DataFixtures.SOURCE;
            List<DataModel.Material> materials = noResults ? List.of() : List.of(
                    new DataModel.Material(source, DataFixtures.CORPUS, false, StrictJson.textSha(DataFixtures.CORPUS)));
            List<Map<String, Object>> citations = noResults ? List.of() : List.of(Map.of("documentId", source.docId(),
                    "versionId", source.versionId(), "chunkId", source.chunkId(), "contextTruncated", false));
            var binding = new DataModel.Binding(registration.runId(), registration.caseId(), registration.attemptId(),
                    batch.datasetSha256(), Long.MAX_VALUE, 1, conversation, turn, registration.questionSha256());
            var snapshot = new DataModel.InputSnapshot(1, binding, "instance", noResults ? null : "observed-model",
                    noResults ? null : "v1", noResults ? List.of() : List.of(new DataModel.Message("user", DataFixtures.item().question())),
                    materials, noResults ? null : "a".repeat(64), noResults ? DataModel.ModelStage.NOT_CALLED : DataModel.ModelStage.MODEL_COMPLETED,
                    noResults ? null : StrictJson.textSha("model-mismatch".equals(mode) ? "other" : answer),
                    noResults ? 0 : answer.codePointCount(0, answer.length()), new DataModel.Usage(null, null),
                    noResults ? DataModel.AnswerKind.NO_RESULTS : DataModel.AnswerKind.MODEL,
                    DataModel.BusinessStage.COMPLETED, DataModel.SettlementStage.CONFIRMED,
                    materials.stream().map(DataModel.Material::source).toList());
            if (!"missing".equals(mode)) {
                var publisher = new OutputPublisher();
                try (var session = publisher.begin(publisher.preflight(shared.resolve("captures").resolve(registration.artifactKey()).toString()))) {
                    session.put("input.json", StrictJson.bytes(snapshot));
                    session.finish();
                }
            }
            saved.clear();
            saved.put("id", turn);
            saved.put("turnSeq", 1);
            saved.put("question", question.path("question").textValue());
            saved.put("answer", "mismatch".equals(mode) ? "changed" : answer);
            saved.put("status", "COMPLETED");
            saved.put("answerKind", noResults ? "NO_RESULTS" : "MODEL");
            saved.put("visibility", "blocked".equals(mode) ? "BLOCKED" : "VISIBLE");
            saved.put("settlementState", "pending".equals(mode) ? "PENDING" : "SETTLED");
            saved.put("savedCodepoints", answer.codePointCount(0, answer.length()));
            saved.put("citations", "source-mismatch".equals(mode) ? List.of() : citations);
            boolean knownUsage = List.of("pending", "missing", "model-mismatch").contains(mode);
            saved.put("promptTokens", knownUsage ? 10L : null);
            saved.put("completionTokens", knownUsage ? 3L : null);
            if ("replay".equals(mode)) {
                byte[] body = StrictJson.bytes(Map.of("conversationId", conversation, "turnId", turn, "turnSeq", 1,
                        "status", "COMPLETED", "replayed", true));
                return turnResponse(conversation, turn, "application/json", body);
            }
            StringBuilder stream = new StringBuilder(": heartbeat\n\n");
            citations.forEach(citation -> stream.append(SseAnswersTest.event("citation", citation)));
            stream.append(SseAnswersTest.event("delta", Map.of("text", answer)));
            if (!"eof".equals(mode)) {
                stream.append(SseAnswersTest.event("pending".equals(mode) ? "error" : "done",
                        Map.of("conversationId", conversation, "turnId", turn, "status", "COMPLETED")));
            }
            return turnResponse(conversation, turn, "text/event-stream", stream.toString().getBytes(StandardCharsets.UTF_8));
        }

        private Response turnResponse(long conversation, String turn, String type, byte[] bytes) {
            if ("body-io".equals(mode)) {
                return new Response(200, HttpHeaders.of(Map.of("Content-Type", List.of(type),
                        "X-Conversation-Id", List.of(Long.toString(conversation)), "X-Turn-Id", List.of(turn)), (name, value) -> true),
                        new InputStream() {
                            @Override
                            public int read() throws IOException {
                                throw new IOException("response lost");
                            }
                        });
            }
            return new Response(200, HttpHeaders.of(Map.of("Content-Type", List.of(type),
                    "X-Conversation-Id", List.of(Long.toString(conversation)), "X-Turn-Id", List.of(turn)), (name, value) -> true),
                    new ByteArrayInputStream(bytes) {
                        @Override
                        public synchronized int read(byte[] buffer, int offset, int length) {
                            return super.read(buffer, offset, Math.min(1, length));
                        }
                    });
        }
    }
}
