package com.zhishiku.kb.answereval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** 串行第一轮采集；不重发生成，任何异常都保留完整用例分母。 */
public final class CollectionRunner {
    public static final String NO_RESULTS = "未找到与问题相关的内容。";
    private final LongSupplier monotonic;
    private final Clock clock;
    private final OutputPublisher publisher;
    private final Runnable poll;

    public CollectionRunner() {
        this(System::nanoTime, Clock.systemUTC(), new OutputPublisher(), () -> {
            try {
                TimeUnit.MILLISECONDS.sleep(25);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new EvaluationFailure(EvaluationFailure.Code.CANCELLED);
            }
        });
    }

    CollectionRunner(LongSupplier monotonic, Clock clock, OutputPublisher publisher, Runnable poll) {
        this.monotonic = monotonic;
        this.clock = clock;
        this.publisher = publisher;
        this.poll = poll;
    }

    public int collect(DatasetReader.Batch batch, CollectionConfig config, CollectionConfig.Credentials credentials,
                       Transport transport, OutputPublisher.Target output) {
        Path shared = LocalFiles.path(config.captureDirectory());
        Path dataTarget = LocalFiles.child(shared, "datasets/" + UUID.randomUUID());
        var dataOutput = publisher.preflight(dataTarget.toString());
        var client = new BusinessClient(transport, config, credentials, monotonic);
        List<DataModel.Answer> answers = new ArrayList<>();
        Map<String, DataModel.FileRef> inputs = new HashMap<>();
        Map<String, Long> identities = new HashMap<>();
        credentials.tokens().keySet().forEach(alias -> identities.put(alias, config.identities().get(alias).expectedUserId()));
        boolean stopped = false;
        boolean generationAttempted = false;
        EvaluationFailure.Code preflightError = null;
        boolean restoreInterrupt = false;
        long contentBytes = 0;
        long answerBytes = 0;
        try {
            client.preflight();
        } catch (EvaluationFailure failure) {
            preflightError = failure.code();
            if (failure.code() == EvaluationFailure.Code.CANCELLED) {
                restoreInterrupt = Thread.interrupted();
            }
        }
        if (preflightError == null) {
            try (var staged = publisher.begin(dataOutput)) {
                batch.frozenFiles().forEach((name, bytes) -> staged.put(name, bytes.bytes()));
                staged.finish();
            }
        }
        try (var staged = publisher.begin(output)) {
            for (DataModel.Case item : batch.cases()) {
                if (stopped) {
                    answers.add(failed(item.caseId(), DataModel.ExecutionStatus.NOT_RUN, null, null));
                    continue;
                }
                if (preflightError != null) {
                    answers.add(failed(item.caseId(), DataModel.ExecutionStatus.FAILED, null, preflightError));
                    stopped = true;
                    continue;
                }
                String attemptId = UUID.randomUUID().toString();
                String requestId = UUID.randomUUID().toString();
                String createRequestId = UUID.randomUUID().toString();
                Long conversation = null;
                var confirmedTurn = new AtomicReference<String>();
                BusinessClient.Saved confirmedSaved = null;
                DataModel.Binding confirmedBinding = null;
                Deadline deadline = new Deadline(monotonic, config.caseTimeoutMs());
                try {
                    conversation = client.create(item.identityAlias(), createRequestId, deadline);
                    var registration = new DataModel.Registration(1, config.runId(), item.caseId(), attemptId,
                            shared.relativize(dataTarget.resolve(batch.manifestFile())).toString().replace((char) 92, '/'),
                            batch.datasetSha256(), config.targetId(), config.instanceId(),
                            identities.get(item.identityAlias()), conversation, 0, StrictJson.textSha(item.question()),
                            clock.millis() + config.caseTimeoutMs(), UUID.randomUUID().toString());
                    register(shared, registration);
                    generationAttempted = true;
                    BusinessClient.Turn turn = client.generate(item.identityAlias(), conversation, requestId, item.question(), deadline,
                            confirmedTurn::set);
                    BusinessClient.Saved saved = client.saved(item.identityAlias(), conversation, confirmedTurn.get(), deadline);
                    if (saved.turnSeq() != 1 || !item.question().equals(saved.question())) {
                        throw new EvaluationFailure(EvaluationFailure.Code.BINDING);
                    }
                    confirmedSaved = saved;
                    // 当前本人读取失败必须先停止；不能用磁盘内容绕过授权或来源屏蔽。
                    if (!"VISIBLE".equals(saved.visibility()) || !"COMPLETED".equals(saved.status())
                            || !"SETTLED".equals(saved.settlementState())) {
                        throw new EvaluationFailure(EvaluationFailure.Code.INCOMPLETE);
                    }
                    DataModel.InputSnapshot snapshot = capture(shared, registration, deadline);
                    verifyBinding(batch, item, registration, turn, saved, snapshot);
                    confirmedBinding = snapshot.binding();
                    verifyContent(batch, turn, saved, snapshot);
                    var attempt = new DataModel.Attempt(requestId, attemptId, conversation, confirmedTurn.get(), createRequestId);
                    var answer = new DataModel.Answer(item.caseId(), DataModel.ExecutionStatus.COMPLETE, attempt,
                            snapshot.binding(), saved.kind(), turn.answer(), StrictJson.textSha(saved.answer()),
                            saved.citations().stream().map(BusinessClient.Citation::source).toList(),
                            snapshot.businessStage(), snapshot.settlementStage(), saved.usage(), null);
                    byte[] snapshotBytes = StrictJson.bytes(snapshot);
                    if (StrictJson.bytes(answer).length > StrictJson.LINE_LIMIT) {
                        throw new EvaluationFailure(EvaluationFailure.Code.LIMIT);
                    }
                    inputs.put(item.caseId(), staged.put("inputs/" + registration.artifactKey() + ".json", snapshotBytes));
                    answers.add(answer);
                    long currentAnswerBytes = StrictJson.bytes(answer).length + 1L;
                    answerBytes += currentAnswerBytes;
                    contentBytes += snapshotBytes.length + currentAnswerBytes;
                    if (answerBytes > StrictJson.FILE_LIMIT - OutputPublisher.CAPTURE_LIMIT - StrictJson.MANIFEST_LIMIT
                            || contentBytes > OutputPublisher.RUN_LIMIT - 2L * OutputPublisher.CAPTURE_LIMIT - StrictJson.MANIFEST_LIMIT) {
                        stopped = true;
                    }
                } catch (EvaluationFailure failure) {
                    DataModel.ExecutionStatus status = failure.code() == EvaluationFailure.Code.CANCELLED
                            ? DataModel.ExecutionStatus.CANCELLED : failure.code() == EvaluationFailure.Code.SUBMISSION_UNKNOWN
                            ? DataModel.ExecutionStatus.UNKNOWN : DataModel.ExecutionStatus.FAILED;
                    answers.add(failed(item.caseId(), status,
                            new DataModel.Attempt(requestId, attemptId, conversation, confirmedTurn.get(), createRequestId),
                            failure.code(), confirmedSaved, confirmedBinding));
                    stopped = true;
                    if (failure.code() == EvaluationFailure.Code.CANCELLED) {
                        restoreInterrupt = Thread.interrupted();
                    }
                }
            }
            var answerFile = staged.put("answers.jsonl", StrictJson.jsonl(answers));
            var manifest = new DataModel.Collection(1, config.runId(), batch.datasetSha256(), "answer-eval-v1",
                    config.artifactId(), StrictJson.sha(StrictJson.bytes(config)), config.model(),
                    batch.dataset().corpusSnapshotId(), DataModel.ClaimState.DECLARED_ONLY, config.aclSnapshotId(),
                    DataModel.ClaimState.DECLARED_ONLY, batch.dataset().sourceType(),
                    batch.cases().stream().map(DataModel.Case::caseId).toList(), identities, answerFile, inputs,
                    answers.stream().allMatch(answer -> answer.status() == DataModel.ExecutionStatus.COMPLETE),
                    generationAttempted ? transport.invocationOrigin() : DataModel.InvocationOrigin.NOT_CALLED);
            staged.put("collection.json", StrictJson.bytes(manifest));
            staged.finish();
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
        return answers.stream().allMatch(answer -> answer.status() == DataModel.ExecutionStatus.COMPLETE) ? 0 : 3;
    }

    private static DataModel.Answer failed(String caseId, DataModel.ExecutionStatus status, DataModel.Attempt attempt,
                                           EvaluationFailure.Code error) {
        return failed(caseId, status, attempt, error, null, null);
    }

    private static DataModel.Answer failed(String caseId, DataModel.ExecutionStatus status, DataModel.Attempt attempt,
                                           EvaluationFailure.Code error, BusinessClient.Saved saved, DataModel.Binding binding) {
        DataModel.BusinessStage business = saved == null ? DataModel.BusinessStage.NOT_CONFIRMED : switch (saved.status()) {
            case "COMPLETED" -> DataModel.BusinessStage.COMPLETED;
            case "FAILED" -> DataModel.BusinessStage.FAILED;
            case "CANCELLED" -> DataModel.BusinessStage.CANCELLED;
            default -> DataModel.BusinessStage.NOT_CONFIRMED;
        };
        DataModel.SettlementStage settlement = saved == null ? DataModel.SettlementStage.NOT_CONFIRMED
                : "SETTLED".equals(saved.settlementState()) ? DataModel.SettlementStage.CONFIRMED
                : "PENDING".equals(saved.settlementState()) ? DataModel.SettlementStage.PENDING : DataModel.SettlementStage.NOT_CONFIRMED;
        return new DataModel.Answer(caseId, status, attempt, binding, saved == null ? null : saved.kind(), null, null, List.of(),
                business, settlement, saved == null ? new DataModel.Usage(null, null) : saved.usage(), error);
    }

    private static void register(Path root, DataModel.Registration registration) {
        Path target = LocalFiles.child(root,
                "registrations/" + registration.expectedUserId() + "-" + registration.conversationId() + ".json");
        Path temporary = target.resolveSibling(UUID.randomUUID() + ".tmp");
        boolean created = false;
        try {
            Files.createDirectories(target.getParent());
            LocalFiles.checkAncestors(target);
            Files.write(temporary, StrictJson.bytes(registration), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            created = true;
            LocalFiles.checkAncestors(target);
            Files.move(temporary, target);
            created = false;
        } catch (IOException | SecurityException failure) {
            throw new EvaluationFailure(EvaluationFailure.Code.OUTPUT);
        } finally {
            if (created) {
                try {
                    LocalFiles.checkAncestors(temporary);
                    Files.delete(temporary);
                } catch (IOException | RuntimeException ignored) {
                    // 清理失败不遍历目录或删除其他对象，临时登记从不被识别为有效登记。
                }
            }
        }
    }

    private DataModel.InputSnapshot capture(Path root, DataModel.Registration registration, Deadline deadline) {
        Path directory = LocalFiles.child(root, "captures/" + registration.artifactKey());
        while (!Files.exists(LocalFiles.child(directory, "complete.json"))) {
            deadline.check();
            poll.run();
        }
        deadline.check();
        var bundle = new BundleFiles(directory);
        return StrictJson.read(bundle.read("input.json", OutputPublisher.CAPTURE_LIMIT).bytes(), DataModel.InputSnapshot.class);
    }

    static void verify(DatasetReader.Batch batch, DataModel.Case item, DataModel.Registration registration,
                       BusinessClient.Turn turn, BusinessClient.Saved saved, DataModel.InputSnapshot snapshot) {
        verifyBinding(batch, item, registration, turn, saved, snapshot);
        verifyContent(batch, turn, saved, snapshot);
    }

    private static void verifyBinding(DatasetReader.Batch batch, DataModel.Case item, DataModel.Registration registration,
                                      BusinessClient.Turn turn, BusinessClient.Saved saved, DataModel.InputSnapshot snapshot) {
        var binding = snapshot.binding();
        if (!binding.runId().equals(registration.runId()) || !binding.caseId().equals(item.caseId())
                || !binding.attemptId().equals(registration.attemptId()) || !binding.datasetSha256().equals(batch.datasetSha256())
                || binding.userId() != registration.expectedUserId() || binding.conversationId() != turn.conversationId()
                || !binding.turnId().equals(turn.turnId()) || !binding.questionSha256().equals(registration.questionSha256())
                || !snapshot.instanceId().equals(registration.instanceId()) || !saved.turnId().equals(turn.turnId())
                || saved.turnSeq() != 1 || !item.question().equals(saved.question())) {
            throw new EvaluationFailure(EvaluationFailure.Code.BINDING);
        }
    }

    private static void verifyContent(DatasetReader.Batch batch, BusinessClient.Turn turn, BusinessClient.Saved saved,
                                      DataModel.InputSnapshot snapshot) {
        batch.requireMaterials(snapshot.materials());
        if (turn.replayed() || turn.error() != null || turn.answer() == null || !turn.answer().equals(saved.answer())
                || saved.savedCodepoints() != turn.answer().codePointCount(0, turn.answer().length())
                || !"COMPLETED".equals(saved.status()) || !"VISIBLE".equals(saved.visibility())
                || !"SETTLED".equals(saved.settlementState()) || !turn.citations().equals(saved.citations())
                || snapshot.businessStage() != DataModel.BusinessStage.COMPLETED
                || snapshot.settlementStage() != DataModel.SettlementStage.CONFIRMED || snapshot.answerKind() != saved.kind()
                || !snapshot.sealedSources().equals(saved.citations().stream().map(BusinessClient.Citation::source).toList())) {
            throw new EvaluationFailure(EvaluationFailure.Code.INCOMPLETE);
        }
        if (saved.kind() == DataModel.AnswerKind.NO_RESULTS) {
            if (!NO_RESULTS.equals(turn.answer()) || !saved.citations().isEmpty() || !snapshot.messages().isEmpty()
                    || !snapshot.materials().isEmpty() || snapshot.modelStage() != DataModel.ModelStage.NOT_CALLED
                    || snapshot.requestSha256() != null || snapshot.answerSha256() != null) {
                throw new EvaluationFailure(EvaluationFailure.Code.BINDING);
            }
        } else if (snapshot.modelStage() != DataModel.ModelStage.MODEL_COMPLETED || snapshot.messages().isEmpty()
                || snapshot.requestSha256() == null || !StrictJson.textSha(turn.answer()).equals(snapshot.answerSha256())
                || snapshot.answerCodePoints() != saved.savedCodepoints() || !snapshot.usage().equals(saved.usage())
                || !snapshot.materials().stream().map(material -> new BusinessClient.Citation(material.source(), material.contextTruncated()))
                        .toList().equals(saved.citations())) {
            throw new EvaluationFailure(EvaluationFailure.Code.BINDING);
        }
    }
}
