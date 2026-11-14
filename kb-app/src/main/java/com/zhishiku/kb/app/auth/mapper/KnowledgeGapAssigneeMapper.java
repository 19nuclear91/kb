package com.zhishiku.kb.app.auth.mapper;

import com.zhishiku.kb.app.auth.model.KnowledgeGapAssigneeViews;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 只查询有效合格人员，不开放完整身份目录，不修改角色或资源授权。 */
@Mapper
public interface KnowledgeGapAssigneeMapper {
    List<KnowledgeGapAssigneeViews.Candidate> candidates(@Param("like") String like,
                                                        @Param("afterId") Long afterId, @Param("fetch") int fetch);

    KnowledgeGapAssigneeViews.Qualification qualification(@Param("userId") long userId);
}
