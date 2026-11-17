package com.zhishiku.kb.ai.evaluation;

import com.zhishiku.kb.ai.chat.RetrievalClient;
import com.zhishiku.kb.ai.model.ModelEvent;
import com.zhishiku.kb.ai.model.PreparedChatRequest;
import com.zhishiku.kb.answereval.DataModel;
import com.zhishiku.kb.answereval.DatasetReader;
import com.zhishiku.kb.answereval.OutputPublisher;
import com.zhishiku.kb.answereval.StrictJson;
import reactor.util.context.ContextView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 每次可信执行的有界状态；没有令牌、内部票据或输出许可。 */
public final class CaptureSession {
    public static final String MATERIALS_KEY = CaptureSession.class.getName() + ".materials";
    private final DataModel.Registration registration;
    private final DataModel.Binding binding;
    private final Consumer<CaptureSession> completed;
    private final MessageDigest outputDigest;
    private DatasetReader.Batch dataset;
    private List<DataModel.Message> messages = List.of();
    private List<DataModel.Material> materials = List.of();
    private List<DataModel.Source> sealedSources = List.of();
    private DataModel.ModelStage modelStage = DataModel.ModelStage.NOT_CALLED;
    private DataModel.BusinessStage businessStage = DataModel.BusinessStage.NOT_CONFIRMED;
    private DataModel.SettlementStage settlementStage = DataModel.SettlementStage.NOT_CONFIRMED;
    private DataModel.AnswerKind answerKind = DataModel.AnswerKind.MODEL;
    private DataModel.Usage usage = new DataModel.Usage(null, null);
    private String model;
    private String promptFormat;
    private String requestSha256;
    private String answerSha256;
    private long answerCodePoints;
    private long answerBytes;
    private boolean invalid;
    private boolean closed;

    CaptureSession(DataModel.Registration registration, DataModel.Binding binding,
                   DatasetReader.Batch dataset, Consumer<CaptureSession> completed) {
        this.registration = registration;
        this.binding = binding;
        this.dataset = dataset;
        this.completed = completed;
        try {
            outputDigest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public static CaptureSession current(ContextView context) {
        return context.getOrDefault(CaptureSession.class, null);
    }

    /** 所有旁路操作失败只使评测失效，不向业务链路传播。 */
    public synchronized void safely(Runnable action) {
        if (closed || invalid) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException failure) {
            invalid = true;
            messages = List.of();
            materials = List.of();
            dataset = null;
        }
    }

    public void input(PreparedChatRequest request, List<RetrievalClient.Citation> actual, byte[] encoded) {
        safely(() -> {
            if (modelStage != DataModel.ModelStage.NOT_CALLED || answerKind != DataModel.AnswerKind.MODEL) {
                throw new IllegalStateException("Invalid capture stage");
            }
            List<DataModel.Material> mapped = actual.stream().map(citation -> new DataModel.Material(
                    source(citation), citation.content(), citation.contextTruncated(), StrictJson.textSha(citation.content()))).toList();
            dataset.requireMaterials(mapped);
            List<DataModel.Message> sent = request.messages().stream()
                    .map(message -> new DataModel.Message(message.role(), message.content())).toList();
            if ((long) encoded.length + StrictJson.bytes(mapped).length > OutputPublisher.CAPTURE_LIMIT - 64 * 1024) {
                throw new IllegalStateException("Capture limit");
            }
            messages = sent;
            materials = mapped;
            requestSha256 = StrictJson.sha(encoded);
            model = request.model();
            promptFormat = request.formatVersion();
            modelStage = DataModel.ModelStage.INPUT_READY;
            dataset = null;
        });
    }

    public void subscribed() {
        safely(() -> {
            if (modelStage == DataModel.ModelStage.INPUT_READY) {
                modelStage = DataModel.ModelStage.REQUEST_SUBSCRIBED;
            }
        });
    }

    public void event(ModelEvent event) {
        safely(() -> {
            if (event instanceof ModelEvent.Delta delta) {
                byte[] text = delta.text().getBytes(StandardCharsets.UTF_8);
                answerBytes += text.length;
                if (answerBytes > OutputPublisher.CAPTURE_LIMIT) {
                    throw new IllegalStateException("Capture limit");
                }
                outputDigest.update(text);
                answerCodePoints += delta.text().codePointCount(0, delta.text().length());
                usage = new DataModel.Usage(null, null);
            } else if (event instanceof ModelEvent.FinalUsage finalUsage) {
                usage = new DataModel.Usage(finalUsage.promptTokens(), finalUsage.completionTokens());
            }
        });
    }

    public void modelCompleted() {
        safely(() -> {
            if (modelStage != DataModel.ModelStage.REQUEST_SUBSCRIBED) {
                throw new IllegalStateException("Invalid capture stage");
            }
            modelStage = DataModel.ModelStage.MODEL_COMPLETED;
            answerSha256 = HexFormat.of().formatHex(outputDigest.digest());
        });
    }

    public void modelFailed(boolean cancelled) {
        safely(() -> {
            if (modelStage != DataModel.ModelStage.MODEL_COMPLETED && modelStage != DataModel.ModelStage.UNSUPPORTED) {
                modelStage = cancelled ? DataModel.ModelStage.CANCELLED : DataModel.ModelStage.MODEL_FAILED;
            }
        });
    }

    public void sealed(boolean noResults, List<RetrievalClient.Citation> sources, boolean supported) {
        safely(() -> {
            sealedSources = sources.stream().map(CaptureSession::source).toList();
            answerKind = noResults ? DataModel.AnswerKind.NO_RESULTS : DataModel.AnswerKind.MODEL;
            if (noResults) {
                dataset = null;
                modelStage = DataModel.ModelStage.NOT_CALLED;
            } else if (!supported) {
                dataset = null;
                modelStage = DataModel.ModelStage.UNSUPPORTED;
            }
        });
    }

    public void businessSaved(String status) {
        safely(() -> {
            businessStage = DataModel.BusinessStage.valueOf(status);
            settlementStage = DataModel.SettlementStage.PENDING;
        });
    }

    public void settled() {
        safely(() -> settlementStage = DataModel.SettlementStage.CONFIRMED);
    }

    public synchronized void finish() {
        if (closed) {
            return;
        }
        closed = true;
        dataset = null;
        try {
            completed.accept(this);
        } catch (RuntimeException failure) {
            // 队列或存储故障由缺失完整产物体现，不能改变原业务终态。
        }
    }

    synchronized byte[] payload() {
        if (invalid) {
            return StrictJson.bytes(Map.of("schemaVersion", 1, "binding", binding, "error", "CAPTURE_INVALID"));
        }
        return StrictJson.bytes(new DataModel.InputSnapshot(1, binding, registration.instanceId(), model,
                promptFormat, messages, materials, requestSha256, modelStage, answerSha256, answerCodePoints,
                usage, answerKind, businessStage, settlementStage, sealedSources));
    }

    synchronized String fileName() {
        return invalid ? "failure.json" : "input.json";
    }

    DataModel.Registration registration() {
        return registration;
    }

    static DataModel.Source source(RetrievalClient.Citation citation) {
        return new DataModel.Source(citation.docId(), citation.versionId(), citation.chunkId());
    }

    @Override
    public String toString() {
        return "CaptureSession[redacted]";
    }
}
