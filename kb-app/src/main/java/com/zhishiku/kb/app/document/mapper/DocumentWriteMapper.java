package com.zhishiku.kb.app.document.mapper;

import com.zhishiku.kb.app.document.model.DocumentEntity;
import com.zhishiku.kb.app.document.model.DocumentVersionEntity;
import com.zhishiku.kb.app.document.model.ParseTaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DocumentWriteMapper {

    @Select("SELECT 1 FROM kb_document WHERE id = #{documentId} AND status <> 'deleted' FOR UPDATE")
    Integer lockDocument(@Param("documentId") long documentId);

    @Select("SELECT COALESCE(MAX(version_no), 0) + 1 FROM kb_document_version WHERE doc_id = #{documentId}")
    Integer nextVersionNo(@Param("documentId") long documentId);

    @Insert("""
            INSERT INTO kb_document
                (space_id, title, security_level, status, owner_id)
            VALUES
                (#{spaceId}, #{title}, #{securityLevel}, 'draft', #{ownerId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertDocument(DocumentEntity document);

    @Insert("""
            INSERT INTO kb_document_version
                (doc_id, version_no, object_key, file_name, file_size,
                 content_hash, mime_type, index_status, uploader_user_id, publication_policy, publication_protocol)
            SELECT d.id, #{versionNo}, #{objectKey}, #{fileName}, #{fileSize},
                   #{contentHash}, #{mimeType}, 'pending', #{uploaderUserId}, s.publication_policy, 'VERSIONED_REVIEW'
            FROM kb_document d JOIN kb_space s ON s.id = d.space_id
            WHERE d.id = #{docId} AND d.status NOT IN ('deleted', 'archived') AND s.archived = FALSE
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertVersion(DocumentVersionEntity version);

    @Insert("""
            INSERT INTO kb_parse_task (version_id, status, attempts, max_attempts)
            VALUES (#{versionId}, 'pending', 0, 3)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertParseTask(ParseTaskEntity task);
}
