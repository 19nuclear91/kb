package com.zhishiku.kb.app.search.mapper;

import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.app.search.model.SearchFilters;
import com.zhishiku.kb.app.document.metadata.model.DocumentFileTypes;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 混合检索。<b>本接口对应的 SQL 是整个系统安全性最敏感的地方。</b>
 *
 * <p>SQL 见 {@code resources/mapper/SearchMapper.xml}。放 XML 而非注解，
 * 是因为权限谓词需要作为可复用片段注入到每一路召回中——这正是它最重要的性质。
 *
 * <p>三条不可违反的规则：
 * <ol>
 *   <li><b>权限条件必须出现在每一路召回的 WHERE 里</b>，而不是召回后再过滤。
 *       先 top-k 再过滤会让结果被大量丢弃，召回质量断崖下跌。</li>
 *   <li><b>空主体集合 = 看不到任何东西</b>。SQL 里对应 {@code FALSE} 分支，
 *       绝不允许"参数为空则跳过条件"。</li>
 *   <li><b>密级与 ACL 是"与"关系</b>：通过 ACL 只是必要条件，
 *       还要 {@code security_level <= clearance}。</li>
 * </ol>
 */
@Mapper
public interface SearchMapper {

    /**
     * BM25 + 向量双路召回，在 SQL 内完成 RRF 融合。
     *
     * <p>返回的是<b>融合后的候选集</b>，还需交给 rerank 重排后才给 LLM
     * （见《架构决策记录.md》3.3）。因此 {@code candidates} 应取较大值
     * （如 50~100），而不是最终要展示的 top-k。
     *
     * @param queryText     用户查询原文，由 ParadeDB 按索引字段的分词器切词
     * @param queryVector   pgvector 字面量，形如 {@code "[0.1,0.2,...]"}；
     *                      为 null 时退化为纯 BM25（向量尚未回填时可用）
     * @param spaceIds      可见空间。<b>空表示看不到任何空间</b>，不是"不限"
     * @param principalKeys 有效主体复合键，用于文档级例外授权
     * @param clearance     用户可见的最高密级
     * @param candidates    每路召回的候选数
     */
    default List<ChunkHit> hybridSearch(String queryText, String queryVector, List<Long> spaceIds,
                                       List<String> principalKeys, short clearance, int candidates, String profileId) {
        return filteredSearch(queryText, queryVector, spaceIds, principalKeys, clearance, candidates, profileId,
                SearchFilters.empty(), List.of());
    }

    List<ChunkHit> filteredSearch(@Param("queryText") String queryText,
                                @Param("queryVector") String queryVector,
                                @Param("spaceIds") List<Long> spaceIds,
                                @Param("principalKeys") List<String> principalKeys,
                                @Param("clearance") short clearance,
                                @Param("candidates") int candidates,
                                @Param("profileId") String profileId,
                                @Param("filters") SearchFilters filters,
                                @Param("fileTypeRules") List<DocumentFileTypes.Rule> fileTypeRules);
}
