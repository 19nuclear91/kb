package com.zhishiku.kb.app.search.expansion;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Objects;

/** 不可变执行与说明事实，不保留快照、令牌、身份绑定或预算回调。 */
public record SearchQueryPlan(
        Mode mode,
        String originalQuery,
        String effectiveQuery,
        String executionText,
        Long dictionaryRevision,
        List<AppliedTerm> appliedTerms,
        List<Suggestion> suggestions,
        List<Selection> selections
) {
    public static final String EXECUTION_FORMAT = "literal-append-v1";

    public enum Mode {
        ASSISTED, ORIGINAL
    }

    public enum SelectionKind {
        CORRECTION, DISAMBIGUATION
    }

    public SearchQueryPlan {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(originalQuery);
        Objects.requireNonNull(effectiveQuery);
        Objects.requireNonNull(executionText);
        appliedTerms = List.copyOf(appliedTerms);
        suggestions = List.copyOf(suggestions);
        selections = List.copyOf(selections);
        if (mode == Mode.ASSISTED ? dictionaryRevision == null || dictionaryRevision < 0
                : dictionaryRevision != null || !originalQuery.equals(effectiveQuery) || !originalQuery.equals(executionText)
                || !appliedTerms.isEmpty() || !suggestions.isEmpty() || !selections.isEmpty()) {
            throw new IllegalArgumentException("查询计划模式事实无效");
        }
    }

    /**
     * 已提交或核验后的原文选择；请求解码仍要求精确 JSON 整数。
     * @param kind 明确的人工选择种类
     * @param start 原文 code point 起点
     * @param end 原文 code point 终点
     * @param termId 目标稳定身份，响应中按十进制字符串输出
     */
    @Schema(description = "本次已核验的累计人工选择")
    public record Selection(
            @Schema(description = "CORRECTION 纠错；DISAMBIGUATION 歧义选择")
            SelectionKind kind,
            @Schema(description = "原文 Unicode code point 起点，包含，从 0 开始")
            int start,
            @Schema(description = "原文 Unicode code point 终点，不包含")
            int end,
            @Schema(description = "目标词条 ID，精确十进制字符串", type = "string")
            @JsonSerialize(using = ToStringSerializer.class)
            long termId
    ) {
        @Override
        public String toString() {
            return "SearchSelection[redacted]";
        }
    }

    /**
     * 已采纳组与实际补充表达；相同组重复位置共享表达清单。
     * @param start 原文 code point 起点
     * @param end 原文 code point 终点
     * @param matchedText 原文完整片段
     * @param termId 当前稳定身份
     * @param termVersion 同一快照实体版本
     * @param canonicalName 当前标准名称
     * @param expressions 实际加入 executionText 的表达，不包含已在有效原文中的键
     */
    @Schema(description = "本次采用的词条及实际补充表达")
    public record AppliedTerm(
            @Schema(description = "原文 Unicode code point 起点，包含，从 0 开始")
            int start,
            @Schema(description = "原文 Unicode code point 终点，不包含")
            int end,
            @Schema(description = "原文中匹配的完整片段")
            String matchedText,
            @Schema(description = "词条 ID，精确十进制字符串", type = "string")
            @JsonSerialize(using = ToStringSerializer.class)
            long termId,
            @Schema(description = "同一词典快照中的实体版本，精确十进制字符串", type = "string")
            @JsonSerialize(using = ToStringSerializer.class)
            long termVersion,
            @Schema(description = "该词条的标准名称")
            String canonicalName,
            @Schema(description = "实际加入执行查询的补充表达，不含已存在于有效查询中的表达")
            List<String> expressions
    ) {
        public AppliedTerm {
            Objects.requireNonNull(matchedText);
            Objects.requireNonNull(canonicalName);
            expressions = List.copyOf(expressions);
            if (termId <= 0 || termVersion < 0 || start < 0 || end <= start || matchedText.codePointCount(0, matchedText.length()) != end - start) {
                throw new IllegalArgumentException("已采用关系事实无效");
            }
        }

        @Override
        public String toString() {
            return "AppliedSearchTerm[redacted]";
        }
    }

    /**
     * 当前启用含义的最小候选，不包含其他别名或文档事实。
     * @param termId 稳定组身份
     * @param termVersion 同一快照版本
     * @param canonicalName 当前标准名称
     * @param description 当前释义
     */
    @Schema(description = "当前启用的候选含义，不附文档或成员信息")
    public record Option(
            @Schema(description = "候选词条 ID，精确十进制字符串", type = "string")
            @JsonSerialize(using = ToStringSerializer.class)
            long termId,
            @Schema(description = "同一词典快照中的实体版本，精确十进制字符串", type = "string")
            @JsonSerialize(using = ToStringSerializer.class)
            long termVersion,
            @Schema(description = "候选含义的标准名称")
            String canonicalName,
            @Schema(description = "用于区分候选含义的释义")
            String description
    ) {
        public Option {
            Objects.requireNonNull(canonicalName);
            Objects.requireNonNull(description);
            if (termId <= 0 || termVersion < 0) {
                throw new IllegalArgumentException("建议含义事实无效");
            }
        }

        @Override
        public String toString() {
            return "SearchSuggestionOption[redacted]";
        }
    }

    /**
     * 仍待明确选择的原文片段。
     * @param kind 建议种类
     * @param start 原文 code point 起点
     * @param end 原文 code point 终点
     * @param sourceText 原文完整片段
     * @param options 按稳定身份排列的完整有界候选
     */
    @Schema(description = "仍需用户明确选择的原文片段")
    public record Suggestion(
            @Schema(description = "CORRECTION 纠错建议；DISAMBIGUATION 歧义建议")
            SelectionKind kind,
            @Schema(description = "原文 Unicode code point 起点，包含，从 0 开始")
            int start,
            @Schema(description = "原文 Unicode code point 终点，不包含")
            int end,
            @Schema(description = "需要选择的完整原文片段")
            String sourceText,
            @Schema(description = "按稳定身份排列的候选；纠错 1 项，歧义 2–8 项")
            List<Option> options
    ) {
        public Suggestion {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(sourceText);
            options = List.copyOf(options);
            if (start < 0 || end <= start || sourceText.codePointCount(0, sourceText.length()) != end - start
                    || (kind == SelectionKind.CORRECTION ? options.size() != 1 : options.size() < 2 || options.size() > 8)) {
                throw new IllegalArgumentException("待确认建议事实无效");
            }
        }

        @Override
        public String toString() {
            return "SearchSuggestion[redacted]";
        }
    }

    /**
     * 由计划及独立签发的凭据生成的完整辅助投影。
     * @param mode 本次模式
     * @param originalQuery 最初精确原文
     * @param effectiveQuery 仅明确选择后替换的原文
     * @param dictionaryRevision 一致词典修订，ORIGINAL 为 null
     * @param appliedTerms 实际已采纳关系
     * @param suggestions 仍待选择的建议
     * @param selections 已核验累计选择
     * @param selectionContext 仍有建议时的上下文，否则 null
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "本次辅助检索的完整解释，不能作为文档访问凭据")
    public record Assistance(
            @Schema(description = "ASSISTED 使用词典辅助；ORIGINAL 按原文检索")
            Mode mode,
            @Schema(description = "用户最初提交的精确原文")
            String originalQuery,
            @Schema(description = "仅经用户明确选择后替换的有效查询")
            String effectiveQuery,
            @Schema(description = "本次一致词典修订号，精确十进制字符串；ORIGINAL 为 null", type = "string", nullable = true)
            @JsonSerialize(using = ToStringSerializer.class)
            Long dictionaryRevision,
            @Schema(description = "已采用词条及实际补充表达；ORIGINAL 为空")
            List<AppliedTerm> appliedTerms,
            @Schema(description = "尚未采纳、需要用户选择的建议；ORIGINAL 为空")
            List<Suggestion> suggestions,
            @Schema(description = "已核验的累计人工选择；ORIGINAL 为空")
            List<Selection> selections,
            @Schema(description = "仍有建议时签发的绑定凭据，下次选择时原样提交；否则为 null", nullable = true)
            String selectionContext
    ) {
        public Assistance {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(originalQuery);
            Objects.requireNonNull(effectiveQuery);
            appliedTerms = List.copyOf(appliedTerms);
            suggestions = List.copyOf(suggestions);
            selections = List.copyOf(selections);
            if (mode == Mode.ASSISTED ? dictionaryRevision == null || dictionaryRevision < 0
                    : dictionaryRevision != null || !originalQuery.equals(effectiveQuery)
                    || !appliedTerms.isEmpty() || !suggestions.isEmpty() || !selections.isEmpty()) {
                throw new IllegalArgumentException("辅助投影模式事实无效");
            }
            if (suggestions.isEmpty() ? selectionContext != null
                    : selectionContext == null || selectionContext.isEmpty() || selectionContext.length() > 4096) {
                throw new IllegalArgumentException("辅助投影上下文无效");
            }
        }

        @Override
        public String toString() {
            return "SearchAssistance[redacted]";
        }
    }

    public Assistance assistance(String selectionContext) {
        return new Assistance(mode, originalQuery, effectiveQuery, dictionaryRevision, appliedTerms, suggestions, selections, selectionContext);
    }

    @Override
    public String toString() {
        return "SearchQueryPlan[redacted]";
    }
}
