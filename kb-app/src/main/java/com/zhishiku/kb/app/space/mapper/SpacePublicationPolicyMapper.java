package com.zhishiku.kb.app.space.mapper;

import com.zhishiku.kb.app.space.model.SpacePublicationPolicy;
import com.zhishiku.kb.common.review.PublicationPolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SpacePublicationPolicyMapper {
    @Select("SELECT id AS space_id, publication_policy AS policy, version FROM kb_space WHERE id = #{spaceId}")
    SpacePublicationPolicy find(@Param("spaceId") long spaceId);

    @Select("SELECT id AS space_id, publication_policy AS policy, version FROM kb_space WHERE id = #{spaceId} FOR UPDATE")
    SpacePublicationPolicy lock(@Param("spaceId") long spaceId);

    @Update("""
            UPDATE kb_space SET publication_policy = #{policy}, version = version + 1, updated_at = now()
            WHERE id = #{spaceId} AND version = #{expectedVersion}
            """)
    int update(@Param("spaceId") long spaceId, @Param("policy") PublicationPolicy policy,
               @Param("expectedVersion") long expectedVersion);
}
