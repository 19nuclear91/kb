package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentMapper;
import com.zhishiku.kb.app.document.model.DocumentCitationRow;
import com.zhishiku.kb.app.document.model.DocumentCitationView;
import com.zhishiku.kb.app.document.model.DocumentCitationView.Chunk;
import com.zhishiku.kb.app.document.model.DocumentCitationView.Status;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.PermissionScope;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * 固定版本引用的只读核对，每次读取解析主体一次，再分别按 view 和 download 解析权限。
 */
@Service
public class DocumentCitationService {
    private final DocumentMapper mapper;
    private final PermissionService permissions;

    public DocumentCitationService(DocumentMapper mapper, PermissionService permissions) {
        this.mapper = mapper;
        this.permissions = permissions;
    }

    /**
     * 仅在原版本当前可查看时返回片段状态，不猜测缺失分块的继任者。
     *
     * @param userId     服务端认证的用户 ID
     * @param documentId 引用文档 ID
     * @param versionId  引用版本 ID，不允许省略后回退当前版本
     * @param chunkId    引用分块 ID
     * @return 授权原版本下的分块核对结果
     */
    public DocumentCitationView find(long userId, long documentId, long versionId, long chunkId) {
        if (documentId <= 0 || versionId <= 0 || chunkId <= 0) {
            throw ApiException.badRequest("文档、版本和分块 ID 必须为正整数");
        }
        var handle = permissions.principalOf(userId);
        PermissionScope view = permissions.resolve(handle, PermissionService.PERM_VIEW);
        if (view.seesNothing()) {
            throw unavailable();
        }
        DocumentCitationRow row = mapper.findCitation(documentId, versionId, chunkId,
                view.visibleSpaceIdList(), Arrays.asList(view.principalKeys()), view.clearanceLevel());
        if (row == null) {
            throw unavailable();
        }

        Chunk chunk = row.matchedChunkId() == null ? null : new Chunk(row.chunkIdx(), row.content(),
                row.pageNo(), row.headingPath(), row.structureType(), row.sourceLineStart(), row.sourceLineEnd(),
                row.sourceCharStart(), row.sourceCharEnd(), row.sourceLocator());
        String versionPath = "/api/documents/" + documentId + "/versions/" + versionId;
        PermissionScope download = permissions.resolve(handle, PermissionService.PERM_DOWNLOAD);
        boolean downloadable = !download.seesNothing() && mapper.findFile(documentId, versionId,
                download.visibleSpaceIdList(), Arrays.asList(download.principalKeys()), download.clearanceLevel(),
                PermissionService.PERM_DOWNLOAD) != null;
        return new DocumentCitationView(chunk == null ? Status.CHUNK_UNAVAILABLE : Status.AVAILABLE,
                row.docId(), row.docTitle(), row.versionId(), row.versionNo(), chunkId, chunk,
                versionPath + "/preview", downloadable ? versionPath + "/download" : null);
    }

    private static ApiException unavailable() {
        return ApiException.notFound("文档或版本不存在，或当前用户无权访问");
    }
}
