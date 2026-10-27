package com.zhishiku.kb.app.embedding.mapper;

import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EmbeddingProfileMapper {
    @Select("""
            SELECT profile_id, provider, model_source, model, model_revision, dimension, distance,
                   query_transform_version, document_transform_version, query_task_type, document_task_type, adapter_version
              FROM kb_embedding_profile WHERE profile_id = #{id}
            """)
    EmbeddingProfile find(@Param("id") String id);

    @Select("""
            SELECT profile_id, provider, model_source, model, model_revision, dimension, distance,
                   query_transform_version, document_transform_version, query_task_type, document_task_type, adapter_version
              FROM kb_embedding_profile ORDER BY profile_id
            """)
    List<EmbeddingProfile> list();

    @Insert("""
            INSERT INTO kb_embedding_profile (profile_id, fingerprint, provider, model_source, model,
                model_revision, dimension, distance, query_transform_version, document_transform_version,
                query_task_type, document_task_type, adapter_version)
            VALUES (#{p.profileId}, #{fingerprint}, #{p.provider}, #{p.modelSource}, #{p.model},
                #{p.modelRevision}, #{p.dimension}, #{p.distance}, #{p.queryTransformVersion},
                #{p.documentTransformVersion}, #{p.queryTaskType}, #{p.documentTaskType}, #{p.adapterVersion})
            ON CONFLICT (profile_id) DO NOTHING
            """)
    int register(@Param("p") EmbeddingProfile profile, @Param("fingerprint") String fingerprint);
}
