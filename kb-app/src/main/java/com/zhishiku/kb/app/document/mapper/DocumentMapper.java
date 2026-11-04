package com.zhishiku.kb.app.document.mapper;

import com.zhishiku.kb.app.document.model.DocumentCitationRow;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import com.zhishiku.kb.app.document.model.DocumentFile;
import com.zhishiku.kb.app.document.model.DocumentProgress;
import com.zhishiku.kb.app.document.model.DocumentSummary;
import com.zhishiku.kb.app.document.model.DocumentVersionView;
import com.zhishiku.kb.common.conversation.SourceKey;
import com.zhishiku.kb.common.review.VersionAccessPurpose;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档查询与生命周期写入。所有读取方法都接收已解析的权限范围。
 */
@Mapper
public interface DocumentMapper {
    List<SourceKey> visibleVersions(@Param("sources") List<SourceKey> sources,
                                    @Param("spaceIds") List<Long> spaceIds,
                                    @Param("principalKeys") List<String> principalKeys,
                                    @Param("clearance") short clearance,
                                    @Param("purpose") VersionAccessPurpose purpose);

    List<DocumentSummary> list(@Param("spaceId") Long spaceId,
                               @Param("spaceIds") List<Long> spaceIds,
                               @Param("principalKeys") List<String> principalKeys,
                               @Param("clearance") short clearance,
                               @Param("aclPermission") String aclPermission,
                               @Param("offset") long offset,
                               @Param("limit") int limit);

    long count(@Param("spaceId") Long spaceId,
               @Param("spaceIds") List<Long> spaceIds,
               @Param("principalKeys") List<String> principalKeys,
               @Param("clearance") short clearance,
               @Param("aclPermission") String aclPermission);

    DocumentSummary findSummary(@Param("documentId") long documentId,
                                @Param("spaceIds") List<Long> spaceIds,
                                @Param("principalKeys") List<String> principalKeys,
                                @Param("clearance") short clearance,
                                @Param("aclPermission") String aclPermission);

    List<DocumentVersionView> listVersions(@Param("documentId") long documentId,
                                           @Param("spaceIds") List<Long> spaceIds,
                                           @Param("principalKeys") List<String> principalKeys,
                                           @Param("clearance") short clearance,
                                           @Param("aclPermission") String aclPermission);

    DocumentProgress findProgress(@Param("documentId") long documentId,
                                  @Param("versionId") Long versionId,
                                  @Param("spaceIds") List<Long> spaceIds,
                                  @Param("principalKeys") List<String> principalKeys,
                                  @Param("clearance") short clearance,
                                  @Param("aclPermission") String aclPermission);

    DocumentFile findFile(@Param("documentId") long documentId,
                          @Param("versionId") Long versionId,
                          @Param("spaceIds") List<Long> spaceIds,
                          @Param("principalKeys") List<String> principalKeys,
                          @Param("clearance") short clearance,
                          @Param("aclPermission") String aclPermission);

    /**
     * 按当前 view 范围读取指定版本，匹配片段缺失时仍返回授权父版本。
     */
    DocumentCitationRow findCitation(@Param("documentId") long documentId,
                                     @Param("versionId") long versionId,
                                     @Param("chunkId") long chunkId,
                                     @Param("spaceIds") List<Long> spaceIds,
                                     @Param("principalKeys") List<String> principalKeys,
                                     @Param("clearance") short clearance);

    List<String> findObjectKeys(@Param("documentId") long documentId);

    int softDelete(@Param("documentId") long documentId);

    /**
     * 带归属前置条件的软删除：文档已不在 {@code expectedSpaceId} 时更新 0 行，
     * 让管理端在不加文档行锁的前提下关闭"判定后被搬迁"的窗口。
     */
    int softDeleteInSpace(@Param("documentId") long documentId,
                          @Param("expectedSpaceId") long expectedSpaceId);

    /**
     * 不带权限范围的归属与状态读取，只用于软删除前置条件未命中时区分"已删除"与"归属变化"。
     */
    DocumentEntity findRawById(@Param("documentId") long documentId);

    int cancelTasks(@Param("documentId") long documentId);

    DocumentVersionView findVersionForEdit(@Param("documentId") long documentId,
                                           @Param("versionId") Long versionId,
                                           @Param("spaceIds") List<Long> spaceIds,
                                           @Param("principalKeys") List<String> principalKeys,
                                           @Param("clearance") short clearance,
                                           @Param("aclPermission") String aclPermission);

    int resetTaskForRetry(@Param("versionId") long versionId);

    /**
     * 记录用户发起的新执行周期；唯一键使重复请求不重复追加事件。
     */
    int insertReplayEvent(@Param("versionId") long versionId,
                          @Param("actorUserId") long actorUserId,
                          @Param("reason") String reason);

    int resetVersionForRetry(@Param("versionId") long versionId);

}
