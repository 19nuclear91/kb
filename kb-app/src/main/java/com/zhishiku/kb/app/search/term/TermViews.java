package com.zhishiku.kb.app.search.term;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/** 管理端安全投影，所有身份、版本及修订均为精确十进制字符串。 */
public final class TermViews {
    private TermViews() {
    }

    /**
     * 管理别名，不包含关联文档信息。
     * @param text 展示表达
     * @param kind 人工关系类型
     * @param ambiguous 当前是否关联多个启用组
     */
    @Schema(description = "术语有类型的别名")
    public record Alias(@Schema(description = "展示表达") String text,
                        @Schema(description = "人工关系类型") TermRows.AliasKind kind,
                        @Schema(description = "当前是否关联多个启用组") boolean ambiguous) {
    }

    /**
     * 当前术语组事实。
     * @param termId 稳定身份
     * @param canonicalName 当前标准名称
     * @param description 简短释义
     * @param canonicalAmbiguous 标准名称是否也关联其他启用组
     * @param aliases 有界不可变别名列表
     * @param enabled 当前自身状态
     * @param version 当前实体版本
     * @param createdAt 创建时间
     * @param updatedAt 修改时间
     */
    @Schema(description = "当前术语组管理投影，不是写回执")
    public record Term(@Schema(description = "稳定身份") String termId,
                       @Schema(description = "当前标准名称") String canonicalName,
                       @Schema(description = "简短释义") String description,
                       @Schema(description = "标准名称是否关联多个启用组") boolean canonicalAmbiguous,
                       @Schema(description = "最多 20 个别名") List<Alias> aliases,
                       @Schema(description = "自身状态") boolean enabled,
                       @Schema(description = "当前实体版本") String version,
                       @Schema(description = "创建时间") OffsetDateTime createdAt,
                       @Schema(description = "修改时间") OffsetDateTime updatedAt) {
        public Term {
            aliases = List.copyOf(aliases);
        }
    }

    /**
     * 错词当前目标的最小管理资料。
     * @param termId 目标稳定身份
     * @param canonicalName 当前标准名称
     * @param description 当前释义
     * @param enabled 目标自身状态
     * @param version 目标实体版本
     */
    @Schema(description = "错词当前目标的最小资料")
    public record Target(@Schema(description = "目标稳定身份") String termId,
                         @Schema(description = "当前标准名称") String canonicalName,
                         @Schema(description = "当前释义") String description,
                         @Schema(description = "目标状态") boolean enabled,
                         @Schema(description = "目标版本") String version) {
    }

    /**
     * 当前错词映射，区分自身与目标状态。
     * @param correctionId 稳定映射身份
     * @param wrongExpression 当前错误表达
     * @param target 当前目标最小资料
     * @param enabled 映射自身状态
     * @param available 当前能否形成建议
     * @param version 当前映射版本
     * @param createdAt 创建时间
     * @param updatedAt 修改时间
     */
    @Schema(description = "当前错词管理投影，不是写回执")
    public record Correction(@Schema(description = "稳定映射身份") String correctionId,
                             @Schema(description = "当前错误表达") String wrongExpression,
                             @Schema(description = "当前目标最小资料") Target target,
                             @Schema(description = "映射自身状态") boolean enabled,
                             @Schema(description = "自身和目标均启用时可形成建议") boolean available,
                             @Schema(description = "当前映射版本") String version,
                             @Schema(description = "创建时间") OffsetDateTime createdAt,
                             @Schema(description = "修改时间") OffsetDateTime updatedAt) {
    }

    /**
     * 单一词典修订内的目录页。
     * @param items 当前有界页
     * @param hasMore 是否有下一页
     * @param nextCursor 受信续页上下文，没有更多时为 null
     * @param dictionaryRevision 当前精确词典修订
     * @param <T> 目录投影种类
     */
    @Schema(description = "同一词典修订的管理目录页")
    public record Page<T>(@Schema(description = "当前有界页") List<T> items,
                          @Schema(description = "是否有下一页") boolean hasMore,
                          @Schema(description = "受信续页上下文") String nextCursor,
                          @Schema(description = "当前精确词典修订") String dictionaryRevision) {
        public Page {
            items = List.copyOf(items);
        }
    }

    /**
     * 当前术语详情及同源修订。
     * @param term 当前术语组
     * @param dictionaryRevision 同一快照修订
     */
    @Schema(description = "当前术语详情及同源修订")
    public record TermDetail(@Schema(description = "当前术语组") Term term,
                             @Schema(description = "同一快照修订") String dictionaryRevision) {
    }

    /**
     * 当前错词详情及同源修订。
     * @param correction 当前映射与目标
     * @param dictionaryRevision 同一快照修订
     */
    @Schema(description = "当前错词详情及同源修订")
    public record CorrectionDetail(@Schema(description = "当前映射与目标") Correction correction,
                                   @Schema(description = "同一快照修订") String dictionaryRevision) {
    }

    /**
     * 单个当前启用组的最小含义，不嵌入其其他关系或文档事实。
     * @param termId 启用组稳定身份
     * @param canonicalName 当前标准名称
     * @param description 当前释义
     * @param version 当前版本
     */
    @Schema(description = "登记表达的单个当前启用含义")
    public record Meaning(@Schema(description = "稳定身份") String termId,
                          @Schema(description = "当前标准名称") String canonicalName,
                          @Schema(description = "当前释义") String description,
                          @Schema(description = "当前版本") String version) {
    }
}
