package com.zhishiku.kb.worker.embedding.model;

/**
 * 重建时读取的分块正文及所属文档版本状态。
 *
 * @param chunkId          分块 ID
 * @param docId            文档 ID
 * @param versionId        文档版本 ID
 * @param content          分块正文
 * @param documentStatus   所属文档的发布状态
 * @param currentVersionId 文档当前已发布版本 ID
 */
public record RebuildChunk(long chunkId, long docId, long versionId, String content,
                           String documentStatus, Long currentVersionId) {
    public boolean inScope(RebuildBatch.Item item) {
        return !"deleted".equals(documentStatus) && currentVersionId != null
                && chunkId == item.chunkId() && docId == item.docId()
                && versionId == item.versionId() && versionId == currentVersionId;
    }

    @Override
    public String toString() {
        return "RebuildChunk[chunkId=" + chunkId + "]";
    }
}
