package com.zhishiku.kb.app.document.metadata.mapper;

import com.zhishiku.kb.app.document.metadata.model.MetadataInputs;
import com.zhishiku.kb.app.document.metadata.model.MetadataSummary;
import com.zhishiku.kb.app.document.metadata.service.DocumentFileTypes;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MetadataQueryMapper {
    @Select("SELECT set_config('statement_timeout', '5000', true)")
    String budget();

    long count(@Param("query") MetadataInputs.Query query, @Param("titlePattern") String titlePattern,
               @Param("rules") List<DocumentFileTypes.Rule> rules, @Param("spaceIds") List<Long> spaceIds,
               @Param("principalKeys") List<String> principalKeys, @Param("clearance") short clearance);

    List<MetadataSummary> list(@Param("query") MetadataInputs.Query query, @Param("titlePattern") String titlePattern,
                               @Param("rules") List<DocumentFileTypes.Rule> rules, @Param("spaceIds") List<Long> spaceIds,
                               @Param("principalKeys") List<String> principalKeys, @Param("clearance") short clearance);
}
