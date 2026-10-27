package com.zhishiku.kb.app.document.mapper;

import com.zhishiku.kb.app.document.model.DocumentFavoriteRow;
import com.zhishiku.kb.app.document.model.DocumentFavoriteViews;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 收藏关系读写；所有内容读取均携带当前权限范围。 */
@Mapper
public interface DocumentFavoriteMapper {
    Long put(@Param("userId") long userId, @Param("documentId") long documentId,
             @Param("spaceIds") List<Long> spaceIds, @Param("principalKeys") List<String> principalKeys,
             @Param("clearance") short clearance);

    int delete(@Param("userId") long userId, @Param("documentId") long documentId);

    List<DocumentFavoriteRow> list(@Param("userId") long userId, @Param("beforeId") Long beforeId,
                                   @Param("limit") int limit, @Param("spaceIds") List<Long> spaceIds,
                                   @Param("principalKeys") List<String> principalKeys,
                                   @Param("clearance") short clearance);

    List<DocumentFavoriteViews.State> states(@Param("userId") long userId,
                                             @Param("documentIds") List<Long> documentIds,
                                             @Param("spaceIds") List<Long> spaceIds,
                                             @Param("principalKeys") List<String> principalKeys,
                                             @Param("clearance") short clearance);
}
