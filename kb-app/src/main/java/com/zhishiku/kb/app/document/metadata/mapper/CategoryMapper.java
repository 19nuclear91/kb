package com.zhishiku.kb.app.document.metadata.mapper;

import com.zhishiku.kb.app.document.metadata.model.CategoryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CategoryMapper {
    @Select("SELECT nextval('kb_document_category_id_seq')")
    long nextId();

    @Insert("""
            INSERT INTO kb_document_category (id, space_id, parent_id, name, normalized_name, sort_order)
            VALUES (#{id}, #{spaceId}, #{parentId}, #{name}, #{normalizedName}, #{sortOrder})
            """)
    int insert(CategoryRow row);

    @Update("""
            UPDATE kb_document_category SET parent_id = #{parentId}, name = #{name},
                normalized_name = #{normalizedName}, sort_order = #{sortOrder}, updated_at = now()
            WHERE space_id = #{spaceId} AND id = #{id}
            """)
    int update(CategoryRow row);

    @Update("""
            UPDATE kb_category_tree_state SET tree_version = tree_version + 1
            WHERE space_id = #{spaceId} AND tree_version = #{version} AND tree_version < 9223372036854775807
            """)
    int advance(@Param("spaceId") long spaceId, @Param("version") long version);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM kb_document_category WHERE space_id = #{spaceId}
                AND parent_id IS NOT DISTINCT FROM CAST(#{parentId} AS bigint)
                AND normalized_name = #{name} AND id <> #{excludeId})
            """)
    boolean duplicate(@Param("spaceId") long spaceId, @Param("parentId") Long parentId,
                      @Param("name") String name, @Param("excludeId") long excludeId);

    @Select("""
            WITH RECURSIVE subtree AS (
                SELECT id, 1 AS depth FROM kb_document_category WHERE space_id = #{spaceId} AND id = #{id}
                UNION ALL
                SELECT c.id, s.depth + 1 FROM kb_document_category c JOIN subtree s ON c.parent_id = s.id
                WHERE c.space_id = #{spaceId} AND s.depth < 9
            ) SELECT COALESCE(max(depth), 0) FROM subtree
            """)
    int height(@Param("spaceId") long spaceId, @Param("id") long id);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM kb_document_category WHERE space_id = #{spaceId} AND parent_id = #{id})
                OR EXISTS (SELECT 1 FROM kb_document WHERE space_id = #{spaceId} AND category_id = #{id})
            """)
    boolean referenced(@Param("spaceId") long spaceId, @Param("id") long id);

    @Delete("DELETE FROM kb_document_category WHERE space_id = #{spaceId} AND id = #{id}")
    int delete(@Param("spaceId") long spaceId, @Param("id") long id);

    @Select("SELECT tree_version FROM kb_category_tree_state WHERE space_id = #{spaceId}")
    Long version(@Param("spaceId") long spaceId);

    @Select("SELECT tree_version FROM kb_category_tree_state WHERE space_id = #{spaceId} FOR SHARE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long shareTree(@Param("spaceId") long spaceId);

    @Select("SELECT tree_version FROM kb_category_tree_state WHERE space_id = #{spaceId} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockTree(@Param("spaceId") long spaceId);

    List<CategoryRow> children(@Param("spaceId") long spaceId, @Param("parentId") Long parentId,
                               @Param("afterOrder") Integer afterOrder, @Param("afterId") Long afterId,
                               @Param("limit") int limit);

    List<CategoryRow> visibleChildren(@Param("spaceId") long spaceId, @Param("parentId") Long parentId,
                                     @Param("afterOrder") Integer afterOrder, @Param("afterId") Long afterId,
                                     @Param("limit") int limit, @Param("spaceIds") List<Long> spaceIds,
                                     @Param("principalKeys") List<String> principalKeys, @Param("clearance") short clearance);

    @Select("""
            WITH RECURSIVE ancestors AS (
                SELECT c.*, 1 AS depth FROM kb_document_category c WHERE space_id = #{spaceId} AND id = #{id}
                UNION ALL
                SELECT p.*, a.depth + 1 FROM kb_document_category p
                JOIN ancestors a ON p.space_id = a.space_id AND p.id = a.parent_id WHERE a.depth < 9
            ) SELECT id, space_id, parent_id, name, normalized_name, sort_order, created_at, updated_at
              FROM ancestors ORDER BY depth DESC
            """)
    List<CategoryRow> path(@Param("spaceId") long spaceId, @Param("id") long id);
}
