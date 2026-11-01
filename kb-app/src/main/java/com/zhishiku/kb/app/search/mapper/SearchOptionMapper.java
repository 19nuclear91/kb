package com.zhishiku.kb.app.search.mapper;

import com.zhishiku.kb.app.search.model.SearchOptions;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 搜索自己的只读目录查询，共用搜索的当前发布及授权谓词。 */
@Mapper
public interface SearchOptionMapper {
    boolean categoryContext(@Param("spaceId") long spaceId, @Param("parentId") Long parentId,
                            @Param("spaceIds") List<Long> spaceIds, @Param("principalKeys") List<String> principalKeys,
                            @Param("clearance") short clearance);

    @Select("SELECT tree_version FROM kb_category_tree_state WHERE space_id = #{spaceId}")
    Long treeVersion(@Param("spaceId") long spaceId);

    List<SearchOptions.CategoryRow> categories(@Param("spaceId") long spaceId, @Param("parentId") Long parentId,
                                               @Param("afterOrder") Integer afterOrder, @Param("afterId") Long afterId,
                                               @Param("limit") int limit, @Param("spaceIds") List<Long> spaceIds,
                                               @Param("principalKeys") List<String> principalKeys, @Param("clearance") short clearance);

    List<SearchOptions.PathRow> paths(@Param("spaceId") long spaceId, @Param("ids") List<Long> ids);

    List<SearchOptions.Tag> tags(@Param("pattern") String pattern, @Param("afterId") Long afterId,
                                 @Param("limit") int limit, @Param("spaceIds") List<Long> spaceIds,
                                 @Param("principalKeys") List<String> principalKeys, @Param("clearance") short clearance);
}
