package com.zhishiku.kb.app.document.mapper;

import com.zhishiku.kb.app.document.model.DocumentAdminView;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 管理清单独立投影，永远不连接版本或内容表。
 */
@Mapper
public interface DocumentAdminQueryMapper {
    @Select("""
            <script>
            SELECT d.id AS document_id, d.space_id, s.archived AS space_archived
              FROM kb_document d JOIN kb_space s ON s.id = d.space_id
             WHERE d.status &lt;&gt; 'deleted'
               AND <choose><when test="global">TRUE</when>
                 <when test="spaces != null and spaces.size() > 0">
                   d.space_id IN <foreach collection="spaces" item="space" open="(" separator="," close=")">#{space}</foreach>
                 </when><otherwise>FALSE</otherwise></choose>
               <if test="spaceId != null">AND d.space_id = #{spaceId}</if>
               <if test="documentId != null">AND d.id = #{documentId}</if>
               <if test="archived != null">AND s.archived = #{archived}</if>
               <if test="afterId != null">AND d.id &lt; #{afterId}</if>
             ORDER BY d.id DESC LIMIT #{fetch}
            </script>
            """)
    @ConstructorArgs({@Arg(column = "document_id", javaType = long.class),
            @Arg(column = "space_id", javaType = long.class), @Arg(column = "space_archived", javaType =
            boolean.class)})
    List<DocumentAdminView> list(@Param("global") boolean global, @Param("spaces") List<Long> spaces,
                                 @Param("spaceId") Long spaceId, @Param("documentId") Long documentId,
                                 @Param("archived") Boolean archived, @Param("afterId") Long afterId,
                                 @Param("fetch") int fetch);
}
