package com.zhishiku.kb.app.storage.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对账用的清理保护及存活版本只读查询。
 *
 * <p>存活定义为文档未被软删除（{@code d.status <> 'deleted'}）：这些版本仍可被
 * 下载、预览与引用，与其文档状态、空间是否归档、是否为当前版本无关。
 * 清理保护另包含所有 ACTIVE 导入尝试，即使已超时也要等待持久失效。
 */
@Mapper
public interface StorageReconciliationMapper {

    /**
     * 返回给定对象键中仍被版本或 ACTIVE 尝试保护的子集；调用方保证输入非空。
     */
    List<String> listLiveObjectKeys(@Param("objectKeys") List<String> objectKeys);

    /**
     * 是否存在任一版本或 ACTIVE 保护；为空时拒绝删除，防止误连错误库清空桶。
     */
    boolean hasLiveReference();

    /**
     * 按版本 ID 倒序取最近 limit 条存活引用对象键，用于缺失抽样。
     */
    List<String> sampleLiveObjectKeys(@Param("limit") int limit);
}
