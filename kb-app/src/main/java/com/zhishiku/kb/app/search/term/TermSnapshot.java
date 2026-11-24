package com.zhishiku.kb.app.search.term;

import java.util.List;
import java.util.Set;

/** 仅在 Reader 的回调内有效的只读词典事实；不提供维护或资源授权。 */
public interface TermSnapshot {
    TermRows.State state();

    Set<String> probe(List<String> keys);

    Relations relations(String key);

    List<TermRows.Alias> aliases(long termId);

    List<Long> owners(String key);

    /**
     * 当前启用含义的最小事实。
     * @param termId 稳定组身份
     * @param canonicalName 标准名称
     * @param canonicalKey 精确比较键
     * @param description 当前释义
     * @param version 当前实体版本
     */
    record Meaning(long termId, String canonicalName, String canonicalKey, String description, long version) {
        @Override
        public String toString() {
            return "TermMeaning[redacted]";
        }
    }

    /**
     * 前九个不同启用组或一个单向纠错目标；第九组仅作超限证据。
     * @param terms 标准名称或别名对应的有界启用组
     * @param correction 单向错词目标，无映射时为 null
     */
    record Relations(List<Meaning> terms, Meaning correction) {
        public Relations {
            terms = List.copyOf(terms);
        }

        @Override
        public String toString() {
            return "TermRelations[redacted]";
        }
    }
}
