package com.zhishiku.kb.app.document.applicability.mapper;

import com.zhishiku.kb.app.document.applicability.model.ApplicabilityChange;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityRow;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityTarget;
import com.zhishiku.kb.common.conversation.SourceKey;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface ApplicabilityMapper {
    ApplicabilityTarget target(@Param("documentId") long documentId, @Param("versionId") long versionId);
    ApplicabilityTarget lockVersion(@Param("documentId") long documentId, @Param("versionId") long versionId);
    ApplicabilityRow get(@Param("documentId") long documentId, @Param("versionId") long versionId);
    List<ApplicabilityRow> batch(@Param("sources") List<SourceKey> sources);
    int insert(ApplicabilityRow row);
    int update(ApplicabilityRow row);
    int appendChange(ApplicabilityChange row);
    List<ApplicabilityChange> history(@Param("documentId") long documentId, @Param("versionId") long versionId,
                                      @Param("beforeTime") OffsetDateTime beforeTime, @Param("beforeId") Long beforeId,
                                      @Param("limit") int limit);
    boolean hasPermission(@Param("documentId") long documentId, @Param("spaceIds") List<Long> spaceIds,
                          @Param("principalKeys") List<String> principalKeys, @Param("clearance") short clearance,
                          @Param("aclPermission") String permission);
}
