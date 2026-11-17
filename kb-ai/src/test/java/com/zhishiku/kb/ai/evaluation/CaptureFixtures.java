package com.zhishiku.kb.ai.evaluation;

import com.zhishiku.kb.ai.chat.RetrievalClient;
import com.zhishiku.kb.ai.conversation.BeginResult;
import com.zhishiku.kb.ai.conversation.ExecutionKey;
import com.zhishiku.kb.ai.model.ChatModelProperties;
import com.zhishiku.kb.ai.model.ChatPromptRenderer;
import com.zhishiku.kb.ai.model.PreparedChatRequest;
import com.zhishiku.kb.answereval.DataModel;
import com.zhishiku.kb.answereval.StrictJson;
import com.zhishiku.kb.answereval.OutputPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

public final class CaptureFixtures {
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);
    public static final String QUESTION = "报销上限？";
    public static final String TEXT = "报销上限为 100 元。🧾";
    public static final DataModel.Source SOURCE = new DataModel.Source(1, 2, 3);

    private CaptureFixtures() {
    }

    public static DataModel.Registration registration(Path root, long conversation) throws IOException {
        Path data = Files.createDirectories(root.resolve("data"));
        byte[] cases = StrictJson.jsonl(List.of(new DataModel.Case("case-1", "employee", QUESTION, List.of("amount"), null)));
        byte[] labels = StrictJson.jsonl(List.of(new DataModel.Label("case-1", DataModel.Expected.ANSWER,
                List.of(new DataModel.Fact("amount", "100 元")), List.of(), null, List.of(), null)));
        byte[] corpus = StrictJson.jsonl(List.of(new DataModel.Corpus(SOURCE, TEXT, StrictJson.textSha(TEXT))));
        Files.write(data.resolve("cases.jsonl"), cases);
        Files.write(data.resolve("labels.jsonl"), labels);
        Files.write(data.resolve("corpus.jsonl"), corpus);
        DataModel.Dataset dataset = new DataModel.Dataset(1, "test", "v1", "corpus",
                DataModel.SourceType.SYNTHETIC, new DataModel.FileRef("cases.jsonl", StrictJson.sha(cases)),
                new DataModel.FileRef("labels.jsonl", StrictJson.sha(labels)),
                new DataModel.FileRef("corpus.jsonl", StrictJson.sha(corpus)), null, List.of("test"), List.of("judge"));
        byte[] bytes = StrictJson.bytes(dataset);
        Files.write(data.resolve("dataset.json"), bytes);
        var registration = new DataModel.Registration(1, "run", "case-1", UUID.randomUUID().toString(),
                "data/dataset.json", StrictJson.sha(bytes), "test", "instance", 1, conversation, 0,
                StrictJson.textSha(QUESTION), CLOCK.millis() + 60_000, UUID.randomUUID().toString());
        writeRegistration(root, registration);
        return registration;
    }

    public static void writeRegistration(Path root, DataModel.Registration registration) throws IOException {
        Path directory = Files.createDirectories(root.resolve("registrations"));
        Files.write(directory.resolve(registration.expectedUserId() + "-" + registration.conversationId() + ".json"),
                StrictJson.bytes(registration));
    }

    public static BeginResult begin(long conversation) {
        var execution = new ExecutionKey(conversation, UUID.randomUUID(), 1, 0, 1, "INTERNAL_TICKET_SENTINEL");
        return new BeginResult(conversation, execution.turnId(), 1, "RUNNING", false, execution);
    }

    public static EvaluationProperties properties(Path root) {
        return new EvaluationProperties(true, "instance", Set.of(1L), root);
    }

    public static RetrievalClient.Citation citation(String text, boolean truncated) {
        return new RetrievalClient.Citation(1, "title", null, 3, text, 2L,
                null, null, null, null, null, null, null, null, null, truncated);
    }

    public static PreparedChatRequest request(List<RetrievalClient.Citation> materials) {
        var properties = new ChatModelProperties();
        properties.setModel("model");
        return new ChatPromptRenderer(properties).single(QUESTION, materials, 100);
    }

    public static DataModel.InputSnapshot snapshot(Path root, DataModel.Registration registration) throws IOException {
        return StrictJson.read(Files.readAllBytes(root.resolve("captures").resolve(registration.artifactKey())
                .resolve("input.json")), DataModel.InputSnapshot.class);
    }

    public record Bound(DataModel.Registration registration, CaptureSession session) {
    }

    public static Bound bound(Path root) throws IOException {
        return bound(root, begin(9), new OutputPublisher(), Runnable::run);
    }

    public static Bound bound(Path root, BeginResult begin, OutputPublisher publisher, Executor writer) throws IOException {
        var registration = registration(root, begin.conversationId());
        var registry = new CaptureRegistry(properties(root), CLOCK, publisher, writer);
        return new Bound(registration, registry.claim(begin, QUESTION));
    }
}
