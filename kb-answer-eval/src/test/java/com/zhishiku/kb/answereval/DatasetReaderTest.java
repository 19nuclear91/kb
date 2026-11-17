package com.zhishiku.kb.answereval;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetReaderTest {
    @TempDir
    Path root;

    @Test
    void readsCompleteDatasetAndRejectsDuplicateAndMissingCases() throws IOException {
        Path manifest = DataFixtures.dataset(root);
        DatasetReader.Batch batch = new DatasetReader().read(manifest.toString());
        assertThat(batch.cases()).containsExactly(DataFixtures.item());
        assertThat(batch.corpus()).containsKey(DataFixtures.SOURCE);
        replaceFile(manifest, "cases", StrictJson.jsonl(List.of(DataFixtures.item(), DataFixtures.item())));
        assertInvalid(manifest);
        DataFixtures.dataset(root);
        replaceFile(manifest, "labels", new byte[0]);
        assertInvalid(manifest);
        DataFixtures.dataset(root);
        ObjectNode item = (ObjectNode) StrictJson.parse(StrictJson.bytes(DataFixtures.item()));
        item.put("system", "UNTRUSTED_INPUT");
        replaceFile(manifest, "cases", StrictJson.bytes(item));
        assertInvalid(manifest);
    }

    @Test
    void freezesValidatedContentAndRejectsDigestChanges() throws IOException {
        Path manifest = DataFixtures.dataset(root);
        DatasetReader.Batch batch = new DatasetReader().read(manifest.toString());
        Files.writeString(root.resolve("cases.jsonl"), "changed");
        assertThat(batch.cases()).containsExactly(DataFixtures.item());
        assertThatThrownBy(() -> new DatasetReader().read(manifest.toString()))
                .isInstanceOf(EvaluationFailure.class).hasMessage("ANSWER_EVAL_ERROR DIGEST");
    }

    @Test
    void deidentifiedDataRequiresApprovalBoundToContentAndTargets() throws IOException {
        Path manifest = DataFixtures.dataset(root);
        ObjectNode node = (ObjectNode) StrictJson.parse(Files.readAllBytes(manifest));
        node.put("sourceType", "HUMAN_APPROVED_DEIDENTIFIED");
        Files.write(manifest, StrictJson.bytes(node));
        assertInvalid(manifest);
        DataModel.Dataset dataset = StrictJson.convert(node, DataModel.Dataset.class);
        node.set("approval", StrictJson.parse(StrictJson.bytes(new DataModel.Approval(
                "reviewer-1", "v1", DatasetReader.approvalDigest(dataset)))));
        Files.write(manifest, StrictJson.bytes(node));
        DatasetReader.Batch batch = new DatasetReader().read(manifest.toString());
        batch.requireTarget("test", false);
        batch.requireTarget("judge", true);
        assertThatThrownBy(() -> batch.requireTarget("unapproved", true))
                .isInstanceOf(EvaluationFailure.class).hasMessage("ANSWER_EVAL_ERROR SOURCE");
        node.withArray("allowedJudgeTargets").add("changed");
        Files.write(manifest, StrictJson.bytes(node));
        assertInvalid(manifest);
        node.put("sourceType", "PRODUCTION");
        Files.write(manifest, StrictJson.bytes(node));
        assertInvalid(manifest);
    }

    @Test
    void onlyExactApprovedTextOrMarkedPrefixCanBeUsed() throws IOException {
        DatasetReader.Batch batch = new DatasetReader().read(DataFixtures.dataset(root).toString());
        batch.requireMaterials(List.of(material(DataFixtures.SOURCE, DataFixtures.CORPUS, false)));
        batch.requireMaterials(List.of(material(DataFixtures.SOURCE, "报销上限", true)));
        for (DataModel.Material material : List.of(
                material(new DataModel.Source(1, 2, 3), DataFixtures.CORPUS, false),
                material(DataFixtures.SOURCE, "报销上限", false),
                material(DataFixtures.SOURCE, "上限", true),
                material(DataFixtures.SOURCE, DataFixtures.CORPUS + "changed", false))) {
            assertThatThrownBy(() -> batch.requireMaterials(List.of(material)))
                    .isInstanceOf(EvaluationFailure.class).hasMessage("ANSWER_EVAL_ERROR SOURCE");
        }
    }

    static void replaceFile(Path manifest, String field, byte[] bytes) throws IOException {
        ObjectNode node = (ObjectNode) StrictJson.parse(Files.readAllBytes(manifest));
        ObjectNode reference = (ObjectNode) node.get(field);
        Files.write(manifest.getParent().resolve(reference.get("file").textValue()), bytes);
        reference.put("sha256", StrictJson.sha(bytes));
        Files.write(manifest, StrictJson.bytes(node));
    }

    private DataModel.Material material(DataModel.Source source, String text, boolean truncated) {
        return new DataModel.Material(source, text, truncated, StrictJson.textSha(text));
    }

    private void assertInvalid(Path manifest) {
        assertThatThrownBy(() -> new DatasetReader().read(manifest.toString()))
                .isInstanceOf(EvaluationFailure.class);
    }
}
