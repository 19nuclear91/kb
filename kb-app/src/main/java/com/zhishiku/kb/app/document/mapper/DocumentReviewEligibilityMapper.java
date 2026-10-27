package com.zhishiku.kb.app.document.mapper;

import com.zhishiku.kb.app.document.model.ReviewerQualification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentReviewEligibilityMapper {
    List<ReviewerQualification> candidates(@Param("documentId") long documentId, @Param("uploaderId") Long uploaderId,
                                           @Param("submittedBy") Long submittedBy, @Param("userId") Long userId);
}
