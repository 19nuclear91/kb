package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.worker.parse.model.ParseExecution;
import com.zhishiku.kb.worker.parse.model.ParseIssue;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import com.zhishiku.kb.worker.storage.ObjectStorage;
import com.zhishiku.kb.worker.telemetry.TaskTelemetry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 原件下载、统一结构化解析、分块和 embedding 的默认流水线。
 */
@Component
public class DefaultParsePipeline implements ParsePipeline {

    private final ObjectStorage objectStorage;
    private final DocumentParser parser;
    private final IngestionProperties props;
    private final EmbeddingClientRegistry clients;
    private TaskTelemetry telemetry = TaskTelemetry.NOOP;

    @Autowired
    public void setTelemetry(TaskTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    public DefaultParsePipeline(ObjectStorage objectStorage, DocumentParser parser,
                                IngestionProperties props, EmbeddingClientRegistry clients) {
        this.objectStorage = objectStorage;
        this.parser = parser;
        this.props = props;
        this.clients = clients;
    }

    @Override
    public ParsedDocument process(ParseTask task,
                                  LeaseHandle lease) throws Exception {
        if (!lease.stillHeld()) throw new LeaseLostException();
        if (!task.hasEmbeddingIdentity()) throw new LeaseLostException();
        if (!parser.isAvailable()) throw new IllegalStateException("第三方解析服务未配置或不可用");
        var client = clients.find(task.embeddingProfileId(), task.embeddingFingerprint())
                .orElseThrow(LeaseLostException::new);
        if (client.role() != EmbeddingRole.DOCUMENT) throw new LeaseLostException();
        var execution = new ParseExecution(task.id(), task.versionId(), task.leaseOwner(),
                task.embeddingEpoch(), task.claimToken(), client.profile());
        if (task.fileSize() == null || task.fileSize() <= 0) {
            throw new IllegalArgumentException("文件大小无效");
        }
        if (task.fileSize() > props.maxFileBytes()) {
            throw new IllegalArgumentException("文件超过 Worker 限制：" + props.maxFileBytes() + " bytes");
        }

        var parsed = telemetry.inStage(TaskTelemetry.Stage.PARSE, () -> {
            com.zhishiku.kb.worker.parse.model.ParsedDocument result;
            try (InputStream input = objectStorage.get(task.objectKey())) {
                result = parser.parseDocument(input, task.fileName(), task.mimeType(), props.maxExtractedChars());
            }
            if (!lease.stillHeld()) {
                throw new LeaseLostException();
            }
            if (result.hasErrors()) {
                ParseIssue issue = result.issues().stream()
                        .filter(item -> item.severity() == ParseIssue.Severity.ERROR)
                        .findFirst().orElseThrow();
                throw new ParseFailureException("文档解析失败 [" + issue.code() + "]: "
                        + issue.message(), new ParsedDocument(List.of(), result.parserName(),
                        result.parserVersion(), result.truncated(), result.issues(), execution));
            }
            return result;
        });

        TextChunker chunker = new TextChunker(props.chunkSize(), props.chunkOverlap());
        List<TextChunker.Chunk> chunks = chunker.split(parsed);
        if (chunks.isEmpty()) throw new IllegalArgumentException("文档没有可索引的文本内容");

        List<String> contents = chunks.stream().map(TextChunker.Chunk::content).toList();
        var generated = telemetry.inStage(TaskTelemetry.Stage.EMBEDDING, () -> {
            var result = client.embed(contents);
            result.require(execution.profile(), EmbeddingRole.DOCUMENT, chunks.size());
            return result;
        });
        List<float[]> vectors = generated.vectors();
        if (!lease.stillHeld()) throw new LeaseLostException();

        List<IndexedChunk> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = vectors.get(i);
            result.add(new IndexedChunk(i, chunks.get(i).content(), chunks.get(i).pageNo(),
                    chunks.get(i).headingPath(), chunks.get(i).source(), chunks.get(i).kind(),
                    chunks.get(i).tableCells(), vector));
        }
        return new ParsedDocument(result, parsed.parserName(), parsed.parserVersion(),
                parsed.truncated(), parsed.issues(), execution);
    }
}
