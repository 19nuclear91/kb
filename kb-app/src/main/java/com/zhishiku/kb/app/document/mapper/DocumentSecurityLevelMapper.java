package com.zhishiku.kb.app.document.mapper;

import com.zhishiku.kb.app.document.model.DocumentSecurityLevelState;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 文档密级管理不依赖正文 ACL，调用者必须执行专用授权。
 */
@Mapper
public interface DocumentSecurityLevelMapper {
    @Select("""
            SELECT id AS document_id, security_level, security_version
              FROM kb_document WHERE id = #{documentId} AND status <> 'deleted'
            """)
    @ConstructorArgs({
            @Arg(column = "document_id", javaType = long.class),
            @Arg(column = "security_level", javaType = short.class),
            @Arg(column = "security_version", javaType = long.class)
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    DocumentSecurityLevelState find(@Param("documentId") long documentId);

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    int lockRequest(@Param("key") String key);

    @Select("""
            SELECT id AS document_id, security_level, security_version
              FROM kb_document WHERE id = #{documentId} AND status <> 'deleted' FOR UPDATE
            """)
    @ConstructorArgs({
            @Arg(column = "document_id", javaType = long.class),
            @Arg(column = "security_level", javaType = short.class),
            @Arg(column = "security_version", javaType = long.class)
    })
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    DocumentSecurityLevelState lockDocument(@Param("documentId") long documentId);

    @Update("""
            UPDATE kb_document SET security_level = #{level}, security_version = security_version + 1,
                   updated_at = now()
             WHERE id = #{documentId} AND status <> 'deleted' AND security_version = #{version}
            """)
    int updateLevel(@Param("documentId") long documentId, @Param("level") short level,
                    @Param("version") long version);

    @Update("""
            UPDATE kb_doc_chunk SET security_level = #{level}
             WHERE doc_id = #{documentId} AND security_level IS DISTINCT FROM #{level}
            """)
    int syncChunks(@Param("documentId") long documentId, @Param("level") short level);
}
