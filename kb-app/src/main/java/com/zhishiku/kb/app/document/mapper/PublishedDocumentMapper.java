package com.zhishiku.kb.app.document.mapper;

import com.zhishiku.kb.app.document.model.PublishedDocumentReference;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 发布资料的窄读取接口；沿用文档当前 view、密级与固定版本门禁，无写入。 */
@Mapper
public interface PublishedDocumentMapper {
    PublishedDocumentReference find(@Param("documentId") long documentId, @Param("versionId") long versionId,
                                     @Param("spaceIds") List<Long> spaceIds, @Param("principalKeys") List<String> principalKeys,
                                     @Param("clearance") short clearance);

    List<PublishedDocumentReference> candidates(@Param("like") String like, @Param("afterId") Long afterId,
                                                @Param("fetch") int fetch, @Param("spaceIds") List<Long> spaceIds,
                                                @Param("principalKeys") List<String> principalKeys, @Param("clearance") short clearance);
}
