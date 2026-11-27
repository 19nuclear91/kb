package com.zhishiku.kb.app.domain.vo.subscription;

import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 个人更新与周报的当前授权视图，不持久化标题或人工说明快照。 */
public final class KnowledgeViews {
    private KnowledgeViews() {
    }

    @Schema(name = "KnowledgeUpdate", description = "本人订阅命中的一次固定正式发布")
    public record Update(
            @Schema(description = "本人更新UUID")
            UUID updateId,
            @Schema(description = "固定文档ID，精确十进制字符串")
            String documentId,
            @Schema(description = "固定发布版本ID，精确十进制字符串")
            String versionId,
            @Schema(description = "文档内发布版本号，从1开始")
            int versionNo,
            @Schema(description = "文档当前授权标题，不是发布时标题快照")
            String title,
            @Schema(description = "实际发布业务时间，ISO-8601 UTC")
            Instant publishedAt,
            @Schema(description = "北京时间所属周一日期，YYYY-MM-DD")
            LocalDate weekStart,
            @Schema(description = "发布时频率：PER_PUBLICATION逐次、WEEKLY每周")
            SubscriptionMode mode
    ) {
    }

    @Schema(name = "KnowledgeUpdateDetail", description = "固定发布版本的当前人工说明和可用对比入口")
    public record Detail(
            @Schema(description = "本人的固定发布记录")
            Update update,
            @Schema(description = "该版本是否有人工变更说明")
            boolean hasNote,
            @Schema(description = "当前人工说明纯文本，未填写为空，不由模型生成")
            String text,
            @Schema(description = "说明修订版本，精确十进制字符串，未填写为0")
            String noteVersion,
            @Schema(description = "说明是否曾在公开后更正")
            boolean corrected,
            @Schema(description = "说明是否在版本公开后首次补写")
            boolean createdAfterVisible,
            @Schema(description = "当前可读的原说明基准或无说明时建议基准ID，不可用为空")
            String baseVersionId,
            @Schema(description = "可读基准的文档内版本号，不可用为空")
            Integer baseVersionNo,
            @Schema(description = "BASE_AVAILABLE有可读基准、NO_BASE无较旧基准、BASE_UNAVAILABLE原说明基准不可读")
            String comparisonStatus
    ) {
    }

    @Schema(name = "KnowledgeDigest", description = "已经生成的本人周报，具体内容每次重新鉴权")
    public record Digest(
            @Schema(description = "本人周报UUID，固定对应一个统计周")
            UUID digestId,
            @Schema(description = "统计周一日期，YYYY-MM-DD，含当天00:00")
            LocalDate weekStart,
            @Schema(description = "下周一日期，YYYY-MM-DD，不含当天00:00")
            LocalDate weekEnd,
            @Schema(description = "站内提醒实际生成时间，ISO-8601 UTC")
            Instant generatedAt
    ) {
    }

    @Schema(name = "KnowledgeDigestGroup", description = "周报中按文档归并的当前可读发布组")
    public record Group(
            @Schema(description = "文档ID，精确十进制字符串")
            String documentId,
            @Schema(description = "文档当前授权标题")
            String title,
            @Schema(description = "该组当前可读发布次数，精确十进制字符串，不含隐藏项")
            String publicationCount,
            @Schema(description = "组内最近可见发布时间，ISO-8601 UTC")
            Instant latestPublishedAt
    ) {
    }

    @Schema(name = "KnowledgeDigestDetail", description = "固定周期周报及有界的当前可读文档组")
    public record DigestDetail(
            @Schema(description = "本人周报固定周期与提醒身份")
            Digest digest,
            @Schema(description = "当前权限过滤后的文档组分页")
            SubscriptionViews.Page<Group> groups
    ) {
    }
}
