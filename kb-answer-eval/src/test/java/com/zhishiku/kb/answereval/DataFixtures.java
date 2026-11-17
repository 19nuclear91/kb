package com.zhishiku.kb.answereval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class DataFixtures {
    static final String UUID = "12345678-1234-1234-1234-123456789abc";
    static final DataModel.Source SOURCE = new DataModel.Source(Long.MAX_VALUE, 2, 3);
    static final String CORPUS = "报销上限是 100 元，需经主管批准。🧾";

    private DataFixtures() {
    }

    static DataModel.Case item() {
        return new DataModel.Case("case-1", "employee", "报销上限是多少？", List.of("amount"), null);
    }

    static DataModel.Label label() {
        return new DataModel.Label("case-1", DataModel.Expected.ANSWER,
                List.of(new DataModel.Fact("amount", "100 元")), List.of(), null, List.of(), null);
    }

    static Path dataset(Path root) throws IOException {
        Files.createDirectories(root);
        byte[] cases = StrictJson.jsonl(List.of(item()));
        byte[] labels = StrictJson.jsonl(List.of(label()));
        byte[] corpus = StrictJson.jsonl(List.of(new DataModel.Corpus(SOURCE, CORPUS, StrictJson.textSha(CORPUS))));
        Files.write(root.resolve("cases.jsonl"), cases);
        Files.write(root.resolve("labels.jsonl"), labels);
        Files.write(root.resolve("corpus.jsonl"), corpus);
        DataModel.Dataset manifest = new DataModel.Dataset(1, "synthetic", "v1", "corpus-v1",
                DataModel.SourceType.SYNTHETIC, new DataModel.FileRef("cases.jsonl", StrictJson.sha(cases)),
                new DataModel.FileRef("labels.jsonl", StrictJson.sha(labels)),
                new DataModel.FileRef("corpus.jsonl", StrictJson.sha(corpus)), null, List.of("test"), List.of("judge"));
        return Files.write(root.resolve("dataset.json"), StrictJson.bytes(manifest));
    }

    static DataModel.Binding binding() {
        return new DataModel.Binding("run", "case-1", UUID, "a".repeat(64),
                Long.MAX_VALUE, 0, Long.MAX_VALUE, UUID, StrictJson.textSha(item().question()));
    }
}
