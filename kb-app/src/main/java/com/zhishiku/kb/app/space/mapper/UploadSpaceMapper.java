package com.zhishiku.kb.app.space.mapper;

import com.zhishiku.kb.app.space.model.UploadSpaceCandidate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 只查询已由服务层解析的空间 edit 范围，空范围必须拒绝匹配。
 */
@Mapper
public interface UploadSpaceMapper {
    /**
     * 按稳定标识读取未归档上传候选。
     *
     * @param spaces 当次请求的空间 edit 范围
     * @param afterId 上页位置
     * @param fetch 有界读取条数
     * @return 最小候选
     */
    @ConstructorArgs({@Arg(column = "space_id", javaType = long.class, id = true),
            @Arg(column = "name", javaType = String.class)})
    @Select("""
            <script>
            SELECT id AS space_id, name FROM kb_space
             WHERE archived = false AND
             <choose>
               <when test="spaces != null and !spaces.isEmpty()">
                 id IN <foreach collection="spaces" item="space" open="(" separator="," close=")">#{space}</foreach>
               </when>
               <otherwise>FALSE</otherwise>
             </choose>
             <if test="afterId != null">AND id &gt; #{afterId}</if>
             ORDER BY id ASC LIMIT #{fetch}
            </script>
            """)
    List<UploadSpaceCandidate> list(@Param("spaces") List<Long> spaces,
                                     @Param("afterId") Long afterId, @Param("fetch") int fetch);
}
