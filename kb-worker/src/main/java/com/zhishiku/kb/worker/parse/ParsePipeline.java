package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.model.DocumentBlock;
import com.zhishiku.kb.worker.parse.model.ParseExecution;
import com.zhishiku.kb.worker.parse.model.ParseIssue;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import com.zhishiku.kb.worker.parse.model.SourceLocation;

import java.util.List;

/**
 * 解析流水线：取原件 → 解析 → 切块 → 向量化 → 写 {@code kb_doc_chunk}。
 *
 * <p>所有原件格式的内容抽取均通过第三方服务完成。流水线负责调用解析接口、
 * 转换统一结构结果、分块和向量化，不内置文件解析引擎。
 */
public interface ParsePipeline {

    /**
     * 处理一个任务。
     *
     * <p>实现须满足两条契约，否则重试机制会产生脏数据：
     *
     * <ol>
     * <li><b>幂等</b>。任务会被重投（租约超时、Worker 崩溃、显式重试），
     *     同一个 versionId 可能被处理多次。写块前应先按 versionId 清除旧块，
     *     或依赖 {@code kb_doc_chunk} 上的 {@code UNIQUE (version_id, chunk_idx)} 做 upsert。
     * <li><b>失败必须抛异常</b>。静默返回会被记为成功，
     *     结果是文档显示"已索引"但检索不到——这类问题在生产上极难发现。
     * </ol>
     *
     * @param task  已领取的任务，租约由调用方维持
     * @param lease 租约状态。长任务应在每个阶段间检查 {@link LeaseHandle#stillHeld()}，
     *              一旦失守就尽快退出，避免与新持有者并发写同一份数据
     * @throws Exception 任何失败都应抛出；调用方负责计数与重投
     */
    ParsedDocument process(ParseTask task, LeaseHandle lease) throws Exception;

    /**
     * 解析完成但尚未发布到数据库的不可变结果。
     *
     * @param chunks        待幂等写入的分块
     * @param parserName    解析器名称；缺省为 {@code unknown}
     * @param parserVersion 解析器版本；缺省为 {@code unknown}
     * @param truncated     正文是否因超限被截断
     * @param issues        解析过程中记录的问题
     * @param execution     任务领取时的执行身份及嵌入配置
     */
    record ParsedDocument(List<IndexedChunk> chunks,
                          String parserName,
                          String parserVersion,
                          boolean truncated,
                          List<ParseIssue> issues,
                          ParseExecution execution) {
        public ParsedDocument {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
            parserName = parserName == null || parserName.isBlank() ? "unknown" : parserName;
            parserVersion = parserVersion == null || parserVersion.isBlank()
                    ? "unknown" : parserVersion;
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public ParsedDocument(List<IndexedChunk> chunks) {
            this(chunks, "unknown", "unknown", false, List.of());
        }

        public ParsedDocument(List<IndexedChunk> chunks, String parserName, String parserVersion,
                              boolean truncated, List<ParseIssue> issues) {
            this(chunks, parserName, parserVersion, truncated, issues, null);
        }

        public boolean hasErrors() {
            return issues.stream().anyMatch(issue -> issue.severity() == ParseIssue.Severity.ERROR);
        }
    }

    /**
     * 一个待幂等写入的分块。
     *
     * @param chunkIndex  分块在文档内的序号
     * @param content     分块正文
     * @param pageNo      来源页码；未知时为 {@code null}
     * @param headingPath 所属标题路径；未知时为 {@code null}
     * @param source      来源坐标；缺省为 {@link SourceLocation#unknown()}
     * @param kind        结构块类型；缺省为 {@code UNKNOWN}
     * @param tableCells  表格单元格；非表格块为空
     * @param embedding   向量表示
     */
    record IndexedChunk(int chunkIndex, String content, Integer pageNo,
                        String headingPath, SourceLocation source,
                        DocumentBlock.Kind kind, List<List<String>> tableCells,
                        float[] embedding) {
        public IndexedChunk(int chunkIndex, String content, Integer pageNo,
                            String headingPath, float[] embedding) {
            this(chunkIndex, content, pageNo, headingPath,
                    SourceLocation.unknown(), DocumentBlock.Kind.UNKNOWN, List.of(), embedding);
        }

        public IndexedChunk(int chunkIndex, String content, Integer pageNo,
                            String headingPath, SourceLocation source, float[] embedding) {
            this(chunkIndex, content, pageNo, headingPath, source,
                    DocumentBlock.Kind.UNKNOWN, List.of(), embedding);
        }

        public IndexedChunk {
            if (source == null) source = SourceLocation.unknown();
            if (kind == null) kind = DocumentBlock.Kind.UNKNOWN;
            tableCells = tableCells == null ? List.of() : tableCells.stream()
                    .map(row -> row == null ? List.<String>of() : row.stream()
                            .map(cell -> cell == null ? "" : cell).toList())
                    .toList();
            embedding = embedding.clone();
        }

        @Override
        public float[] embedding() {
            return embedding.clone();
        }
    }

    /**
     * 租约状态查询句柄。
     */
    interface LeaseHandle {
        /**
         * 本实例是否仍持有该任务的租约。
         *
         * <p>返回 {@code false} 意味着任务已被其他实例领取。此时继续写数据
         * 就是两个实例并发写同一个 versionId 的块，必须停下。
         */
        boolean stillHeld();
    }
}
