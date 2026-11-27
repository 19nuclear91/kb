package com.zhishiku.kb.app.domain.vo.subscription;

import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;
import com.zhishiku.kb.app.enums.subscription.SubscriptionState;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 本人订阅的固定响应结构，资源不可读时不携带目标身份或名称。 */
public final class SubscriptionViews {
    private SubscriptionViews() {
    }

    @Schema(name = "KnowledgeSubscriptionPage", description = "本人有界分页，游标仅绑定条件，不授予资源访问资格")
    public record Page<T>(
            @Schema(description = "本页当前用户可取得的业务项")
            List<T> items,
            @Schema(description = "是否仍有符合本次查询条件的下一页")
            boolean hasMore,
            @Schema(description = "绑定本人、用途与条件的下一页游标，末页为空")
            String nextCursor,
            @Schema(description = "本次读取时间，ISO-8601 UTC时间点")
            Instant asOf
    ) {
        public Page {
            items = List.copyOf(items);
        }
    }

    @Schema(name = "KnowledgeSubscriptionCandidate", description = "当前可订阅目标及本人既有关系")
    public record Candidate(
            @Schema(description = "DOCUMENT文档、SPACE空间、TAG标签")
            SubscriptionKind kind,
            @Schema(description = "目标资源正整数ID的精确十进制字符串")
            String targetId,
            @Schema(description = "当前授权范围内的目标名称")
            String label,
            @Schema(description = "目标是否启用，标签停用后禁止新建订阅")
            boolean enabled,
            @Schema(description = "本人已有订阅关系UUID，尚未订阅为空")
            UUID subscriptionId,
            @Schema(description = "当前或最近订阅代次UUID，尚未订阅为空")
            UUID generationId,
            @Schema(description = "本人关系并发版本的十进制字符串，尚未订阅为0")
            String version,
            @Schema(description = "ACTIVE有效、CANCELLED已取消，尚未订阅为空")
            SubscriptionState state
    ) {
    }

    @Schema(name = "KnowledgeSubscriptionItem", description = "本人订阅管理项；不可访问目标只保留取消所需关系身份")
    public record Item(
            @Schema(description = "本人稳定订阅关系UUID")
            UUID subscriptionId,
            @Schema(description = "本次或最近订阅代次UUID")
            UUID generationId,
            @Schema(description = "DOCUMENT文档、SPACE空间、TAG标签")
            SubscriptionKind kind,
            @Schema(description = "ACTIVE有效、CANCELLED已取消")
            SubscriptionState state,
            @Schema(description = "关系并发版本，精确十进制字符串")
            String version,
            @Schema(description = "目标当前是否可发现；false时隐藏ID与名称")
            boolean available,
            @Schema(description = "当前可发现的目标ID，精确十进制字符串，不可发现为空")
            String targetId,
            @Schema(description = "当前可发现的目标名称，不可发现为空")
            String label,
            @Schema(description = "标签当前启用标记，不可发现为空；停用不取消原订阅")
            Boolean enabled,
            @Schema(description = "关系首次建立时间，ISO-8601 UTC时间点")
            Instant createdAt,
            @Schema(description = "最近订阅或取消时间，ISO-8601 UTC时间点")
            Instant updatedAt
    ) {
    }

    @Schema(name = "KnowledgeSubscriptionPreference", description = "本人全部订阅使用的统一提醒设置")
    public record Preference(
            @Schema(description = "PER_PUBLICATION逐次、WEEKLY每周，首次默认每周")
            SubscriptionMode mode,
            @Schema(description = "设置并发版本，初始0，精确十进制字符串")
            String version,
            @Schema(description = "本设置生效时间，ISO-8601 UTC时间点；未修改过为空")
            Instant effectiveAt,
            @Schema(description = "固定周报时区Asia/Shanghai")
            String timeZone,
            @Schema(description = "固定周报计划：每周一09:00汇总上一业务周")
            String weeklySchedule
    ) {
    }

    @Schema(name = "KnowledgeSubscriptionReceipt", description = "本人原命令最小回执，不附带目标资源身份或名称")
    public record Receipt(
            @Schema(description = "本人请求标识，绑定首次提交的精确意图")
            String requestId,
            @Schema(description = "SUBSCRIBE订阅、CANCEL取消、PREFERENCE修改频率")
            String operation,
            @Schema(description = "原命令对应订阅关系UUID，频率修改为空")
            UUID subscriptionId,
            @Schema(description = "原命令对应订阅代次UUID，频率修改为空")
            UUID generationId,
            @Schema(description = "首次成功后的关系或设置版本，精确十进制字符串")
            String version,
            @Schema(description = "原命令结果ACTIVE有效、CANCELLED已取消；频率修改为空")
            SubscriptionState state,
            @Schema(description = "频率修改结果PER_PUBLICATION逐次、WEEKLY每周；关系命令为空")
            SubscriptionMode mode,
            @Schema(description = "首次成功的业务生效时间，ISO-8601 UTC时间点")
            Instant effectiveAt,
            @Schema(description = "true为原命令匹配重放，不代表重新执行")
            boolean replayed
    ) {
        public Receipt asReplay() {
            return new Receipt(requestId, operation, subscriptionId, generationId, version, state, mode, effectiveAt, true);
        }
    }

    @Schema(name = "KnowledgeSubscriptionReceiptLookup", description = "只读核对本人原命令，不根据当前状态推断写入是否成功")
    public record ReceiptLookup(
            @Schema(description = "正在核对的本人请求标识")
            String requestId,
            @Schema(description = "CONFIRMED已确认回执、UNKNOWN未确认，不等于原请求未执行")
            String status,
            @Schema(description = "已确认的最小原命令回执，未确认时为空")
            Receipt receipt
    ) {
    }
}
