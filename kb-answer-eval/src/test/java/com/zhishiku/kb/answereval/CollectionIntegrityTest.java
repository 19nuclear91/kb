package com.zhishiku.kb.answereval;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionIntegrityTest {
    @TempDir Path root;

    @Test
    void rewrittenChecksumsCannotHideCrossTurnCrossCaseOrUnapprovedInputs() throws Exception {
        var dataset = new DatasetReader().read(DataFixtures.dataset(root.resolve("data")).toString());
        EvaluationFixtures.collection(dataset, root.resolve("original"), DataModel.AnswerKind.MODEL, true);
        for (String mutation : List.of("turn", "case", "material", "missing", "source", "question", "answer")) {
            Map<String, byte[]> files = new LinkedHashMap<>();
            for (String name : List.of("input.json", "answers.jsonl", "collection.json")) {
                files.put(name, Files.readAllBytes(root.resolve("original/" + name)));
            }
            ObjectNode input = (ObjectNode) StrictJson.parse(files.get("input.json"));
            ObjectNode manifest = (ObjectNode) StrictJson.parse(files.get("collection.json"));
            switch (mutation) {
                case "turn" -> ((ObjectNode) input.get("binding")).put("turnId", "22345678-1234-1234-1234-123456789abc");
                case "case" -> ((ObjectNode) input.get("binding")).put("caseId", "case-other");
                case "material" -> {
                    ObjectNode material = (ObjectNode) input.get("materials").get(0);
                    material.put("text", "UNAPPROVED_SECRET").put("sha256", StrictJson.textSha("UNAPPROVED_SECRET"));
                }
                case "missing" -> ((ObjectNode) manifest.get("inputs")).remove("case-1");
                case "source" -> ((ObjectNode) input.get("sealedSources").get(0)).put("chunkId", 999);
                case "question" -> ((ObjectNode) input.get("messages").get(0)).put("content", "不同问题");
                case "answer" -> input.put("answerSha256", "e".repeat(64));
                default -> throw new AssertionError();
            }
            files.put("input.json", StrictJson.bytes(input));
            if (!mutation.equals("missing")) {
                ((ObjectNode) manifest.path("inputs").get("case-1")).put("sha256", StrictJson.sha(files.get("input.json")));
            }
            files.put("collection.json", StrictJson.bytes(manifest));
            var publisher = new OutputPublisher();
            Path target = root.resolve(mutation);
            try (var session = publisher.begin(publisher.preflight(target.toString()))) {
                files.forEach(session::put);
                session.finish();
            }
            assertThatThrownBy(() -> new CollectionReader().read(target.resolve("collection.json").toString(), dataset))
                    .isInstanceOf(EvaluationFailure.class).hasMessageNotContaining("UNAPPROVED_SECRET");
        }
    }
}
