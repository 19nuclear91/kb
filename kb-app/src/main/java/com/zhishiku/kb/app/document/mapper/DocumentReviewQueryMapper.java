package com.zhishiku.kb.app.document.mapper;

import com.zhishiku.kb.app.document.model.ReviewListRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface DocumentReviewQueryMapper {
    List<ReviewListRow> mine(@Param("actor") long actor, @Param("beforeId") Long beforeId, @Param("limit") int limit);
    List<ReviewListRow> pending(@Param("actor") long actor, @Param("beforeId") Long beforeId,
                                @Param("beforeTime") Instant beforeTime, @Param("limit") int limit);
}
