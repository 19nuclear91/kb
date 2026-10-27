package com.zhishiku.kb.app.storage.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对账用的存活引用只读查询。
 *
 * <p>存活定义为文档未被软删除（{@code d.status <> 'deleted'}）：这些版本仍可被
 * 下载、预览与引用，与其文档状态、空间是否归档、是否为当前版本无关。
 */
@Mapper
public interface StorageReconciliationMapper {

    /**
     * 返回给定对象键中仍被存活引用持有的子集；调用方保证输入非空。
     */
    List<String> listLiveObjectKeys(@Param("objectKeys") List<String> objectKeys);

    /**
     * 是否存在任一存活引用；为空时删除阶段必须整体拒绝，防止误连错误库清空桶。
     */
    boolean hasLiveReference();

    /**
     * 按版本 ID 倒序取最近 limit 条存活引用对象键，用于缺失抽样。
     */
    List<String> sampleLiveObjectKeys(@Param("limit") int limit);
}
