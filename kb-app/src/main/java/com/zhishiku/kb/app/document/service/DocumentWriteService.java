package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.document.mapper.DocumentWriteMapper;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import com.zhishiku.kb.app.document.model.DocumentUploadResult;
import com.zhishiku.kb.app.document.model.DocumentVersionEntity;
import com.zhishiku.kb.app.document.model.ParseTaskEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档元数据、版本和解析任务必须在同一个事务中落库。
 */
@Service
public class DocumentWriteService {

    private final DocumentWriteMapper mapper;

    public DocumentWriteService(DocumentWriteMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 在同一个短事务中写入文档、版本和解析任务。
     *
     * @param ownerId       文档所有者
     * @param spaceId       所属知识空间
     * @param title         文档标题
     * @param securityLevel 文档密级
     * @param objectKey     对象存储 key
     * @param fileName      原始文件名
     * @param fileSize      文件大小（字节）
     * @param contentHash   文件 SHA-256
     * @param mimeType      文件 MIME 类型
     * @return 生成的文档、版本和解析任务标识
     */
    @Transactional
    public DocumentUploadResult create(long ownerId, long spaceId, String title, short securityLevel,
                                       String objectKey, String fileName, long fileSize,
                                        String contentHash, String mimeType) {
        requireUploader(ownerId);
        var document = DocumentEntity.builder()
                .spaceId(spaceId)
                .title(title)
                .securityLevel(securityLevel)
                .status("draft")
                .ownerId(ownerId)
                .build();
        mapper.insertDocument(document);
        requireId(document.getId(), "document");

        var version = DocumentVersionEntity.builder()
                .docId(document.getId())
                .versionNo(1)
                .objectKey(objectKey)
                .fileName(fileName)
                .fileSize(fileSize)
                .contentHash(contentHash)
                .mimeType(mimeType)
                .indexStatus("pending")
                .uploaderUserId(ownerId)
                .publicationProtocol("VERSIONED_REVIEW")
                .build();
        requireInserted(mapper.insertVersion(version));
        requireId(version.getId(), "version");

        var task = ParseTaskEntity.builder()
                .versionId(version.getId())
                .status("pending")
                .attempts(0)
                .maxAttempts(3)
                .build();
        mapper.insertParseTask(task);
        requireId(task.getId(), "parse task");

        return new DocumentUploadResult(document.getId(), version.getId(), task.getId(),
                "pending");
    }

    /**
     * 为已有文档创建新版本。锁住文档行后计算版本号，避免并发上传得到相同版本号。
     * 上传者来自认证会话，不能用文档所有者替代；策略由插入语句从实际空间读取。
     */
    @Transactional
    public DocumentUploadResult createVersion(long uploaderUserId, long documentId, String title,
                                              String objectKey, String fileName, long fileSize,
                                               String contentHash, String mimeType) {
        requireUploader(uploaderUserId);
        if (mapper.lockDocument(documentId) == null) {
            throw new IllegalStateException("文档不存在：" + documentId);
        }
        Integer nextVersionNo = mapper.nextVersionNo(documentId);
        if (nextVersionNo == null || nextVersionNo < 1) {
            throw new IllegalStateException("无法生成文档版本号：" + documentId);
        }

        var version = DocumentVersionEntity.builder()
                .docId(documentId)
                .versionNo(nextVersionNo)
                .objectKey(objectKey)
                .fileName(fileName)
                .fileSize(fileSize)
                .contentHash(contentHash)
                .mimeType(mimeType)
                .indexStatus("pending")
                .uploaderUserId(uploaderUserId)
                .publicationProtocol("VERSIONED_REVIEW")
                .build();
        requireInserted(mapper.insertVersion(version));
        requireId(version.getId(), "version");

        var task = ParseTaskEntity.builder()
                .versionId(version.getId())
                .status("pending")
                .attempts(0)
                .maxAttempts(3)
                .build();
        mapper.insertParseTask(task);
        requireId(task.getId(), "parse task");

        return new DocumentUploadResult(documentId, version.getId(), task.getId(),
                "pending");
    }

    private static void requireInserted(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("文档或实际空间已不可写，未创建版本");
        }
    }

    private static void requireUploader(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("Invalid uploader");
    }

    private static void requireId(Long id, String kind) {
        if (id == null) {
            throw new IllegalStateException("数据库没有返回生成的 " + kind + " ID");
        }
    }
}
