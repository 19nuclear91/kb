package com.zhishiku.kb.app.mapper.subscription;

import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeQuery;
import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeRows;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 更新、周报及通知同源授权查询；数据库先过滤再分页与分组。 */
@Mapper
public interface KnowledgeQueryMapper {
    List<KnowledgeRows.Update> updates(KnowledgeQuery query);

    KnowledgeRows.Update update(KnowledgeQuery query);

    boolean eligible(KnowledgeQuery query);

    List<KnowledgeRows.Digest> digests(KnowledgeQuery query);

    KnowledgeRows.Digest digest(KnowledgeQuery query);

    List<KnowledgeRows.Group> groups(KnowledgeQuery query);

    List<KnowledgeRows.Update> members(KnowledgeQuery query);

    KnowledgeRows.Base base(
            @Param("q")
            KnowledgeQuery query,
            @Param("versionId")
            long versionId,
            @Param("baseVersionId")
            Long baseVersionId
    );
}
