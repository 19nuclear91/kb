package com.zhishiku.kb.app.document.mapper;

import com.zhishiku.kb.app.document.model.DocumentCommentTarget;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 评论目标的当次资格事实；view 与 comment 分别按已解析范围下推，空范围为 FALSE。 */
@Mapper
public interface DocumentCommentTargetMapper {
    DocumentCommentTarget facts(@Param("documentId") long documentId, @Param("versionId") long versionId,
                                @Param("viewSpaceIds") List<Long> viewSpaceIds,
                                @Param("commentSpaceIds") List<Long> commentSpaceIds,
                                @Param("principalKeys") List<String> principalKeys,
                                @Param("clearance") short clearance);

    /** 同一事实，并对文档行取共享锁，使评论写入与搬迁、删除、改密级等文档事务线性化。 */
    DocumentCommentTarget lockFacts(@Param("documentId") long documentId, @Param("versionId") long versionId,
                                    @Param("viewSpaceIds") List<Long> viewSpaceIds,
                                    @Param("commentSpaceIds") List<Long> commentSpaceIds,
                                    @Param("principalKeys") List<String> principalKeys,
                                    @Param("clearance") short clearance);
}
