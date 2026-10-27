package com.zhishiku.kb.eval.model;

import java.util.List;
import java.util.Map;

/**
 * 只包含评测必需字段，不保存原始 JSON。
 */
public final class InputModel {
    private InputModel() {
    }

    /**
     * 文档版本身份。
     *
     * @param docId     文档 ID
     * @param versionId 版本 ID
     */
    public record VersionKey(long docId, long versionId) {
    }

    /**
     * 分块身份，顺序由所在数组保存。
     *
     * @param docId     文档 ID
     * @param versionId 版本 ID
     * @param chunkId   分块 ID
     */
    public record Hit(long docId, long versionId, long chunkId) {
        public VersionKey version() {
            return new VersionKey(docId, versionId);
        }
    }

    /**
     * 单个标注场景。
     *
     * @param caseId        受控场景标识
     * @param tags          受控分类
     * @param expectedEmpty 是否预期无结果
     * @param relevance     文档版本等级
     */
    public record LabelCase(String caseId, List<String> tags, boolean expectedEmpty,
                            Map<VersionKey, Integer> relevance) {
        public LabelCase {
            tags = List.copyOf(tags);
            relevance = Map.copyOf(relevance);
        }
    }

    /**
     * 经完整校验的成功结果。
     *
     * @param caseId    场景标识
     * @param hits      前 max(k) 个分块
     * @param totalHits 完整结果数量，用于空结果判定
     * @param latencyMs 输入声明的耗时，未知时为空
     */
    public record ResultCase(String caseId, List<Hit> hits, int totalHits, Double latencyMs) {
        public ResultCase {
            hits = List.copyOf(hits);
        }
    }

    /**
     * 数据集身份。
     *
     * @param datasetId        数据集标识
     * @param datasetVersion   标注版本
     * @param corpusSnapshotId 语料快照标识
     */
    public record Identity(String datasetId, String datasetVersion, String corpusSnapshotId) {
    }

    /**
     * 已加载数据集。
     *
     * @param identity       数据集身份
     * @param manifestSha256 清单摘要
     * @param labelsSha256   标注字节摘要
     * @param cases          全部标注
     */
    public record Dataset(Identity identity, String manifestSha256, String labelsSha256,
                          Map<String, LabelCase> cases) {
        public Dataset {
            cases = Map.copyOf(cases);
        }
    }

    public enum SourceType {SYNTHETIC, RECORDED}

    /**
     * 经身份和覆盖校验的运行。
     *
     * @param runId           运行标识
     * @param configurationId 配置标识
     * @param sourceType      文件声明来源
     * @param manifestSha256  清单摘要
     * @param resultsSha256   结果字节摘要
     * @param cases           成功结果集合
     */
    public record Run(String runId, String configurationId, SourceType sourceType,
                      String manifestSha256, String resultsSha256, Map<String, ResultCase> cases) {
        public Run {
            cases = Map.copyOf(cases);
        }
    }

    /**
     * 完整输入。
     *
     * @param dataset 数据集
     * @param runs    唯一运行集合
     */
    public record Inputs(Dataset dataset, List<Run> runs) {
        public Inputs {
            runs = List.copyOf(runs);
        }
    }
}
