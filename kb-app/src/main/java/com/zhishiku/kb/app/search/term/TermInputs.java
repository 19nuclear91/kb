package com.zhishiku.kb.app.search.term;

import java.util.List;

/** 管理服务接收的规范化意图；不是客户端可提交的内部比较键协议。 */
public final class TermInputs {
    private TermInputs() {
    }

    /**
     * 规范化别名。
     * @param text 保留展示大小写的 NFC 文本
     * @param key 精确比较键
     * @param kind 人工关系类型
     */
    public record Alias(String text, String key, TermRows.AliasKind kind) {
    }

    /**
     * 完整术语内容意图，别名按比较键稳定排序。
     * @param canonicalName 展示名称
     * @param canonicalKey 精确比较键
     * @param description 规范化释义
     * @param aliases 不可变别名集合
     * @param expectedVersion 更新前置版本，创建为 null
     */
    public record TermWrite(String canonicalName, String canonicalKey, String description,
                            List<Alias> aliases, Long expectedVersion) {
        public TermWrite {
            aliases = List.copyOf(aliases);
        }

        @Override
        public String toString() {
            return "TermWrite[redacted]";
        }
    }

    /**
     * 单向错词内容意图。
     * @param wrongExpression 规范化错误表达
     * @param wrongKey 精确比较键
     * @param termId 已确认的目标身份
     * @param expectedVersion 更新前置版本，创建为 null
     */
    public record CorrectionWrite(String wrongExpression, String wrongKey, long termId, Long expectedVersion) {
        @Override
        public String toString() {
            return "CorrectionWrite[redacted]";
        }
    }

    /**
     * 独立启停意图，不隐式编辑内容或错词目标。
     * @param enabled 新自身状态
     * @param expectedVersion 非负前置版本
     */
    public record EnabledWrite(boolean enabled, long expectedVersion) {
    }

    /**
     * 已提交的管理目录条件。
     * @param nameQuery 规范化名称字面查询，null 表示不限
     * @param enabled 自身启用状态，null 表示不限
     * @param limit 每页数量，1～100
     * @param cursor 受信的后续页上下文，null 表示第一页
     */
    public record Query(String nameQuery, Boolean enabled, int limit, String cursor) {
        public Query {
            if (limit < 1 || limit > 100 || (cursor != null && (cursor.isEmpty() || cursor.length() > 4096))) {
                throw TermInput.invalid();
            }
            if (nameQuery != null) {
                nameQuery = TermInput.text(nameQuery, 64, true);
                nameQuery = nameQuery.isEmpty() ? null : nameQuery;
            }
        }

        @Override
        public String toString() {
            return "TermQuery[redacted]";
        }
    }

    /**
     * 按目标术语登记表达查询当前启用含义。
     * @param expression 完整登记表达
     * @param limit 页大小，1～100
     * @param cursor 受信续页上下文
     */
    public record MeaningQuery(String expression, int limit, String cursor) {
        public MeaningQuery {
            expression = TermInput.expression(expression);
            if (limit < 1 || limit > 100 || (cursor != null && (cursor.isEmpty() || cursor.length() > 4096))) {
                throw TermInput.invalid();
            }
        }

        @Override
        public String toString() {
            return "TermMeaningQuery[redacted]";
        }
    }
}
