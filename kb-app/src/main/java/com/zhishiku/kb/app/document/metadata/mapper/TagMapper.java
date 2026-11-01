package com.zhishiku.kb.app.document.metadata.mapper;

import com.zhishiku.kb.app.document.metadata.model.TagRow;
import com.zhishiku.kb.app.document.metadata.model.DocumentTagRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TagMapper {
    @Select("SELECT nextval('kb_document_tag_id_seq')")
    long nextId();

    @Select("""
            SELECT id, name, normalized_name, enabled, version, created_at, updated_at
            FROM kb_document_tag WHERE id = #{id} FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    TagRow lock(@Param("id") long id);

    @Insert("""
            INSERT INTO kb_document_tag (id, name, normalized_name, enabled)
            VALUES (#{id}, #{name}, #{normalizedName}, true)
            ON CONFLICT (normalized_name) DO NOTHING
            """)
    int insert(TagRow row);

    @Update("""
            UPDATE kb_document_tag SET name = #{name}, normalized_name = #{normalizedName}, enabled = #{enabled},
                version = version + 1, updated_at = now()
            WHERE id = #{id} AND version = #{version} AND version < 9223372036854775807
            """)
    int update(TagRow row);

    List<TagRow> list(@Param("pattern") String pattern, @Param("enabled") Boolean enabled,
                      @Param("afterId") Long afterId, @Param("limit") int limit);

    List<TagRow> visible(@Param("pattern") String pattern, @Param("afterId") Long afterId, @Param("limit") int limit,
                         @Param("spaceIds") List<Long> spaceIds, @Param("principalKeys") List<String> principalKeys,
                         @Param("clearance") short clearance);

    List<TagRow> share(@Param("ids") List<Long> ids);

    List<DocumentTagRow> forDocuments(@Param("ids") List<Long> ids);

    @Select("""
            SELECT t.id, t.name, t.normalized_name, t.enabled, t.version, t.created_at, t.updated_at
            FROM kb_document_tag t JOIN kb_document_tag_relation r ON r.tag_id = t.id
            WHERE r.document_id = #{documentId} ORDER BY t.id
            """)
    List<TagRow> forDocument(@Param("documentId") long documentId);
}
