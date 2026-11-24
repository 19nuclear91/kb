package com.zhishiku.kb.app.search.term;

import java.time.OffsetDateTime;

/** 词典持久事实；别名独立映射，避免联接重复实体行。 */
public final class TermRows {
    private TermRows() {
    }

    public enum AliasKind {
        SYNONYM, ABBREVIATION, FORMER_NAME
    }

    public enum EntityKind {
        TERM, CORRECTION
    }

    public enum Operation {
        SEARCH_TERM_CREATE(EntityKind.TERM, true), SEARCH_TERM_UPDATE(EntityKind.TERM, false),
        SEARCH_TERM_ENABLED(EntityKind.TERM, false), SEARCH_CORRECTION_CREATE(EntityKind.CORRECTION, true),
        SEARCH_CORRECTION_UPDATE(EntityKind.CORRECTION, false), SEARCH_CORRECTION_ENABLED(EntityKind.CORRECTION, false);

        private final EntityKind kind;
        private final boolean create;

        Operation(EntityKind kind, boolean create) {
            this.kind = kind;
            this.create = create;
        }

        public EntityKind kind() {
            return kind;
        }

        public boolean create() {
            return create;
        }
    }

    /**
     * 一致快照的修订与数据库时间。
     * @param revision 非负词典修订
     * @param databaseTime 数据库时间，供上下文签发与到期核验
     */
    public record State(long revision, OffsetDateTime databaseTime) {
    }

    /**
     * 稳定术语组事实。
     * @param id 稳定正整数身份
     * @param canonicalName NFC 展示名称
     * @param canonicalKey ASCII 折叠比较键
     * @param description 简短释义
     * @param enabled 自身启用状态
     * @param version 非负实体版本
     * @param createdAt 创建时间
     * @param updatedAt 修改时间
     */
    public record Term(long id, String canonicalName, String canonicalKey, String description,
                       boolean enabled, long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /**
     * 单组有类型的别名事实。
     * @param termId 所属术语稳定身份
     * @param aliasText NFC 展示文本
     * @param lookupKey ASCII 折叠比较键
     * @param kind 人工等价关系类型
     */
    public record Alias(long termId, String aliasText, String lookupKey, AliasKind kind) {
    }

    /**
     * 单个表达的当前启用组歧义事实，不包含组列表或命中数。
     * @param lookupKey 精确比较键
     * @param ambiguous 是否至少关联两个启用组，null 为损坏事实
     */
    public record Expression(String lookupKey, Boolean ambiguous) {
    }

    /**
     * 人工登记的单向错词事实。
     * @param id 稳定正整数身份
     * @param wrongExpression 错误展示表达
     * @param wrongKey 精确比较键
     * @param targetTermId 目标术语组身份
     * @param enabled 映射自身状态，不含目标状态
     * @param version 非负实体版本
     * @param createdAt 创建时间
     * @param updatedAt 修改时间
     */
    public record Correction(long id, String wrongExpression, String wrongKey, long targetTermId,
                             boolean enabled, long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public boolean available(Term target) {
            return enabled && target != null && target.id() == targetTermId && target.enabled();
        }
    }

    /**
     * 不可变的词典命令账本行，不通过自动字符串投影输出绑定或回执。
     * @param requestId 词典域唯一请求标识
     * @param operation 精确维护操作
     * @param actorUserId 首次操作者
     * @param targetKind 目标种类
     * @param targetId 原意图目标，创建为 null
     * @param expectedVersion 原意图前置版本，创建为 null
     * @param digestVersion 完整意图摘要编码版本
     * @param inputDigest 规范化意图摘要
     * @param receipt 首次最小 JSON 回执
     * @param createdAt 首次提交时间
     */
    public record Command(String requestId, Operation operation, long actorUserId, EntityKind targetKind,
                          Long targetId, Long expectedVersion, int digestVersion, String inputDigest,
                          String receipt, OffsetDateTime createdAt) {
        @Override
        public String toString() {
            return "TermCommand[redacted]";
        }
    }
}
