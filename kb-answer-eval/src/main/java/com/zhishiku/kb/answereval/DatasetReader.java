package com.zhishiku.kb.answereval;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 完整读取、冻结并核对整个批次，调用方只能在本步骤成功后联网。 */
public final class DatasetReader {
    public record Batch(DataModel.Dataset dataset, String datasetSha256, List<DataModel.Case> cases,
                        Map<String, DataModel.Label> labels, Map<DataModel.Source, DataModel.Corpus> corpus,
                        Map<String, LocalFiles.Frozen> frozenFiles, String manifestFile) {
        public Batch {
            cases = List.copyOf(cases);
            labels = Map.copyOf(labels);
            corpus = Map.copyOf(corpus);
            frozenFiles = Map.copyOf(frozenFiles);
        }

        public void requireTarget(String target, boolean judge) {
            List<String> allowed = judge ? dataset.allowedJudgeTargets() : dataset.allowedTestTargets();
            if (!allowed.contains(target)) {
                throw new EvaluationFailure(EvaluationFailure.Code.SOURCE);
            }
        }

        public void requireMaterials(List<DataModel.Material> materials) {
            HashSet<DataModel.Source> seen = new HashSet<>();
            for (DataModel.Material material : materials) {
                DataModel.Corpus approved = corpus.get(material.source());
                if (approved == null || !seen.add(material.source())
                        || (material.contextTruncated() ? !approved.text().startsWith(material.text())
                        : !approved.text().equals(material.text()))) {
                    throw new EvaluationFailure(EvaluationFailure.Code.SOURCE);
                }
            }
        }

        @Override
        public String toString() {
            return "Batch[datasetSha256=" + datasetSha256 + "]";
        }
    }

    public Batch read(String manifest) {
        Path path = LocalFiles.path(manifest);
        LocalFiles.Frozen frozen = LocalFiles.read(path, StrictJson.MANIFEST_LIMIT);
        DataModel.Dataset dataset = StrictJson.read(frozen.bytes(), DataModel.Dataset.class);
        LocalFiles.Frozen caseBytes = LocalFiles.read(path.getParent(), dataset.cases(), StrictJson.FILE_LIMIT);
        LocalFiles.Frozen labelBytes = LocalFiles.read(path.getParent(), dataset.labels(), StrictJson.FILE_LIMIT);
        LocalFiles.Frozen corpusBytes = LocalFiles.read(path.getParent(), dataset.corpus(), StrictJson.FILE_LIMIT);
        List<DataModel.Case> cases = StrictJson.lines(caseBytes.bytes(), DataModel.Case.class, StrictJson.CASE_LIMIT);
        List<DataModel.Label> labels = StrictJson.lines(labelBytes.bytes(), DataModel.Label.class, StrictJson.CASE_LIMIT);
        List<DataModel.Corpus> corpus = StrictJson.lines(corpusBytes.bytes(), DataModel.Corpus.class, 100_000);
        Checks.require(!cases.isEmpty());
        Checks.unique(cases.stream().map(DataModel.Case::caseId).toList());
        Checks.unique(labels.stream().map(DataModel.Label::caseId).toList());
        Map<String, DataModel.Label> labelMap = new LinkedHashMap<>();
        labels.forEach(label -> labelMap.put(label.caseId(), label));
        Checks.require(labelMap.keySet().equals(new HashSet<>(cases.stream().map(DataModel.Case::caseId).toList())));
        for (DataModel.Case item : cases) {
            DataModel.Injection injection = labelMap.get(item.caseId()).injection();
            Checks.require(item.injectionId() == null ? injection == null
                    : injection != null && item.injectionId().equals(injection.injectionId()));
        }
        Map<DataModel.Source, DataModel.Corpus> corpusMap = new LinkedHashMap<>();
        for (DataModel.Corpus item : corpus) {
            Checks.require(corpusMap.putIfAbsent(item.source(), item) == null);
        }
        verifyApproval(dataset);
        Map<String, LocalFiles.Frozen> files = new LinkedHashMap<>();
        files.put(path.getFileName().toString(), frozen);
        Checks.require(files.putIfAbsent(dataset.cases().file(), caseBytes) == null);
        Checks.require(files.putIfAbsent(dataset.labels().file(), labelBytes) == null);
        Checks.require(files.putIfAbsent(dataset.corpus().file(), corpusBytes) == null);
        return new Batch(dataset, frozen.sha256(), cases, labelMap, corpusMap, files, path.getFileName().toString());
    }

    public static String approvalDigest(DataModel.Dataset dataset) {
        return StrictJson.sha(StrictJson.bytes(Map.of(
                "datasetId", dataset.datasetId(), "datasetVersion", dataset.datasetVersion(),
                "corpusSnapshotId", dataset.corpusSnapshotId(), "sourceType", dataset.sourceType(),
                "cases", dataset.cases(), "labels", dataset.labels(), "corpus", dataset.corpus(),
                "allowedTestTargets", dataset.allowedTestTargets(), "allowedJudgeTargets", dataset.allowedJudgeTargets())));
    }

    private static void verifyApproval(DataModel.Dataset dataset) {
        if (dataset.sourceType() == DataModel.SourceType.HUMAN_APPROVED_DEIDENTIFIED && dataset.approval() == null) {
            throw new EvaluationFailure(EvaluationFailure.Code.SOURCE);
        }
        if (dataset.approval() != null && !dataset.approval().contentSha256().equals(approvalDigest(dataset))) {
            throw new EvaluationFailure(EvaluationFailure.Code.SOURCE);
        }
    }
}
