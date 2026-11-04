package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentMapper;
import com.zhishiku.kb.app.document.model.DocumentDetail;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import com.zhishiku.kb.app.document.model.DocumentFile;
import com.zhishiku.kb.app.document.model.DocumentPage;
import com.zhishiku.kb.app.document.model.DocumentProgress;
import com.zhishiku.kb.app.document.model.DocumentSummary;
import com.zhishiku.kb.app.document.model.DocumentUploadResult;
import com.zhishiku.kb.app.document.model.DocumentVersionView;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.storage.ObjectKeys;
import com.zhishiku.kb.app.storage.ObjectStorage;
import com.zhishiku.kb.app.storage.StorageProperties;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.security.PermissionScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 文档查询、版本管理、重试和删除。所有对象存储访问都先完成数据库权限校验。
 */
@Service
public class DocumentManagementService {

    private static final Logger log = LoggerFactory.getLogger(DocumentManagementService.class);

    private final DocumentMapper mapper;
    private final PermissionService permissionService;
    private final ObjectStorage objectStorage;
    private final StorageProperties storageProperties;
    private final DocumentWriteService writeService;
    private final EmbeddingControlService embeddingControl;
    private final ReviewCancellationService reviewCancellation;

    public DocumentManagementService(DocumentMapper mapper,
                                     PermissionService permissionService,
                                     ObjectStorage objectStorage,
                                     StorageProperties storageProperties,
                                      DocumentWriteService writeService, EmbeddingControlService embeddingControl,
                                      ReviewCancellationService reviewCancellation) {
        this.mapper = mapper;
        this.permissionService = permissionService;
        this.objectStorage = objectStorage;
        this.storageProperties = storageProperties;
        this.writeService = writeService;
        this.embeddingControl = embeddingControl;
        this.reviewCancellation = reviewCancellation;
    }

    public DocumentPage list(long userId, Long spaceId, int page, int size) {
        PermissionScope scope = permissionService.resolve(userId, PermissionService.PERM_VIEW);
        List<Long> spaces = scope.visibleSpaceIdList();
        List<String> principals = Arrays.asList(scope.principalKeys());
        long total = mapper.count(spaceId, spaces, principals, scope.clearanceLevel(), PermissionService.PERM_VIEW);
        if (total == 0) {
            return new DocumentPage(page, size, 0, List.of());
        }
        long offset = (long) (page - 1) * size;
        List<DocumentSummary> items = mapper.list(spaceId, spaces, principals, scope.clearanceLevel(),
                PermissionService.PERM_VIEW, offset, size);
        return new DocumentPage(page, size, total, items);
    }

    public DocumentDetail detail(long userId, long documentId) {
        PermissionScope scope = permissionService.resolve(userId, PermissionService.PERM_VIEW);
        DocumentSummary summary = requireSummary(documentId, scope, PermissionService.PERM_VIEW);
        List<DocumentVersionView> versions = mapper.listVersions(documentId,
                scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()), scope.clearanceLevel(),
                PermissionService.PERM_VIEW);
        return new DocumentDetail(summary, versions);
    }

    public DocumentProgress progress(long userId, long documentId, Long versionId) {
        PermissionScope scope = permissionService.resolve(userId, PermissionService.PERM_VIEW);
        DocumentProgress progress = mapper.findProgress(documentId, versionId,
                scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()),
                scope.clearanceLevel(), PermissionService.PERM_VIEW);
        if (progress == null) {
            throw ApiException.notFound("文档或版本不存在，或当前用户无权查看");
        }
        return withWaitingReason(progress, embeddingControl.current());
    }

    @Transactional
    public DocumentProgress retry(long userId, long documentId, Long versionId) {
        PermissionScope scope = permissionService.resolve(userId, PermissionService.PERM_EDIT);
        DocumentVersionView version = mapper.findVersionForEdit(documentId, versionId,
                scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()),
                scope.clearanceLevel(), PermissionService.PERM_EDIT);
        if (version == null) {
            throw ApiException.notFound("文档或版本不存在，或当前用户无编辑权限");
        }
        var control = embeddingControl.lockShared();
        if (mapper.resetTaskForRetry(version.getId()) == 0) {
            throw ApiException.badRequest("当前版本没有可重试的失败任务");
        }
        mapper.insertReplayEvent(version.getId(), userId, "用户请求重新解析");
        mapper.resetVersionForRetry(version.getId());
        PermissionScope view = permissionService.resolve(userId, PermissionService.PERM_VIEW);
        DocumentProgress progress = mapper.findProgress(documentId, version.getId(),
                view.visibleSpaceIdList(), Arrays.asList(view.principalKeys()),
                view.clearanceLevel(), PermissionService.PERM_VIEW);
        if (progress == null) {
            // edit 只证明可以提交；不可读版本仅返回本次受理事实。
            progress = new DocumentProgress();
            progress.setDocId(documentId);
            progress.setVersionId(version.getId());
            progress.setIndexStatus("pending");
            progress.setTaskStatus("pending");
        }
        return withWaitingReason(progress, control);
    }

    @Transactional
    public void delete(long userId, long documentId) {
        PermissionScope scope = permissionService.resolve(userId, PermissionService.PERM_EDIT);
        requireSummary(documentId, scope, PermissionService.PERM_EDIT);
        deleteAuthorized(documentId, userId, null);
    }

    /**
     * 不含资格判定的删除主体，供用户端与管理端共用；调用方必须已经完成自己的授权。
     *
     * <p>两条入口共用这一段而不是各自复制：删除效果必须完全一致，任务取消或审核取消的
     * 后续修正不能只落一边。
     *
     * @param documentId      被删文档
     * @param actorUserId     操作者，写入审核取消记录
     * @param expectedSpaceId 软删除的空间前置条件。管理端按预读归属判定 manage 后传入它，
     *                        文档在判定后被搬走时更新 0 行并以 409 拒绝；用户端的 edit
     *                        范围已含归属，传 {@code null} 沿用无前置条件的语句
     */
    @Transactional
    public void deleteAuthorized(long documentId, long actorUserId, Long expectedSpaceId) {
        List<String> objectKeys = mapper.findObjectKeys(documentId);
        // 与 Worker 发布事务保持 task -> document 的锁顺序，避免删除和发布互相等待。
        mapper.cancelTasks(documentId);
        int deleted = expectedSpaceId == null
                ? mapper.softDelete(documentId)
                : mapper.softDeleteInSpace(documentId, expectedSpaceId);
        if (deleted == 0) {
            if (expectedSpaceId != null) {
                DocumentEntity current = mapper.findRawById(documentId);
                if (current != null && !"deleted".equals(current.getStatus())) {
                    throw ApiException.conflict("文档归属已变化，请重新读取后再决定是否删除");
                }
            }
            throw ApiException.notFound("文档不存在或已经删除");
        }
        reviewCancellation.cancelDocument(documentId, actorUserId);
        // 只有事务提交后才删对象，避免数据库回滚而原件已经消失。生产环境仍应有对象存储对账任务。
        Runnable cleanup = () -> cleanupObjects(documentId, objectKeys);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            // 便于非 Spring 调用方和单元测试；正式 HTTP 调用会走上面的 afterCommit 分支。
            cleanup.run();
        }
    }

    public List<DocumentVersionView> versions(long userId, long documentId) {
        PermissionScope scope = permissionService.resolve(userId, PermissionService.PERM_VIEW);
        requireSummary(documentId, scope, PermissionService.PERM_VIEW);
        return mapper.listVersions(documentId, scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()),
                scope.clearanceLevel(), PermissionService.PERM_VIEW);
    }

    /**
     * 返回已通过 download/view 权限校验的对象定位信息。
     */
    public DocumentFile file(long userId, long documentId, Long versionId, String permission) {
        PermissionScope scope = permissionService.resolve(userId, permission);
        DocumentFile file = mapper.findFile(documentId, versionId, scope.visibleSpaceIdList(),
                Arrays.asList(scope.principalKeys()), scope.clearanceLevel(), permission);
        if (file == null) {
            throw ApiException.notFound("文档或版本不存在，或当前用户无权访问");
        }
        return file;
    }

    /**
     * 仅接受已经由 {@link #file(long, long, Long, String)} 返回的定位信息。
     */
    public InputStream open(DocumentFile file) {
        return objectStorage.get(file.getObjectKey());
    }

    /**
     * 创建新版本。新版本解析完成前不会替换当前已发布版本。
     */
    public DocumentUploadResult uploadVersion(long userId, long documentId, MultipartFile file) {
        PermissionScope scope = permissionService.resolve(userId, PermissionService.PERM_EDIT);
        DocumentSummary document = requireSummary(documentId, scope, PermissionService.PERM_EDIT);
        validateUpload(file);

        String fileName = DocumentUploadService.safeFileName(file.getOriginalFilename());
        String contentType = DocumentUploadService.inspectFile(file, storageProperties.maxUploadBytes());
        String hash = DocumentUploadService.sha256(file);
        String objectKey =
                ObjectKeys.DOCUMENTS_PREFIX + UUID.randomUUID() + DocumentUploadService.extensionOf(fileName);
        try {
            try (InputStream input = file.getInputStream()) {
                objectStorage.put(objectKey, input, file.getSize(), contentType, hash);
            }
            try {
                return writeService.createVersion(userId, documentId, document.getTitle(), objectKey, fileName,
                        file.getSize(), hash, contentType);
            } catch (RuntimeException databaseFailure) {
                try {
                    objectStorage.delete(objectKey);
                } catch (RuntimeException cleanupFailure) {
                    databaseFailure.addSuppressed(cleanupFailure);
                }
                throw databaseFailure;
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败", e);
        }
    }

    private DocumentSummary requireSummary(long documentId, PermissionScope scope, String permission) {
        DocumentSummary summary = mapper.findSummary(documentId, scope.visibleSpaceIdList(),
                Arrays.asList(scope.principalKeys()), scope.clearanceLevel(), permission);
        if (summary == null) {
            throw ApiException.notFound("文档不存在，或当前用户无权访问");
        }
        return summary;
    }

    private DocumentProgress withWaitingReason(DocumentProgress progress,
                                               EmbeddingControl control) {
        if (control == null) throw new IllegalStateException("embedding control unavailable");
        boolean queued = "pending".equals(progress.getTaskStatus()) || "running".equals(progress.getTaskStatus());
        progress.setWaitingReason(!control.active() && queued && !"indexed".equals(progress.getIndexStatus())
                ? "EMBEDDING_MAINTENANCE" : null);
        return progress;
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("上传文件不能为空");
        }
        if (file.getSize() > storageProperties.maxUploadBytes()) {
            throw ApiException.badRequest("文件超过大小限制：" + storageProperties.maxUploadBytes() + " bytes");
        }
    }

    private void cleanupObjects(long documentId, List<String> objectKeys) {
        for (String objectKey : objectKeys) {
            try {
                objectStorage.delete(objectKey);
            } catch (RuntimeException e) {
                log.warn("删除文档原件失败，需由对象存储对账任务补偿: documentId={} objectKey={}",
                        documentId, objectKey, e);
            }
        }
    }
}
