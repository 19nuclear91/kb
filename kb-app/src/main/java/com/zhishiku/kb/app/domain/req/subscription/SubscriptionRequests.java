package com.zhishiku.kb.app.domain.req.subscription;

import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** 本人订阅的固定请求结构，实际入口使用有界严格解析。 */
public final class SubscriptionRequests {
    private SubscriptionRequests() {
    }

    @Schema(name = "KnowledgeSubscribeRequest", description = "建立或重新激活本人订阅，从新的生效位置起接收发布")
    public record Subscribe(
            @Schema(description = "DOCUMENT文档、SPACE空间、TAG标签")
            SubscriptionKind kind,
            @Schema(description = "目标资源正整数ID的精确十进制字符串")
            String targetId,
            @Schema(description = "读取到的关系版本，首次0，精确十进制字符串")
            String expectedVersion
    ) {
    }

    @Schema(name = "KnowledgeCancelRequest", description = "取消指定本人订阅代次，停止尚未生成的提醒")
    public record Cancel(
            @Schema(description = "原订阅代次UUID，不得改绑新代次")
            UUID generationId,
            @Schema(description = "读取到的关系版本，至少1，精确十进制字符串")
            String expectedVersion
    ) {
    }

    @Schema(name = "KnowledgePreferenceRequest", description = "修改本人全部订阅的统一频率，仅影响之后发布")
    public record Preference(
            @Schema(description = "PER_PUBLICATION逐次、WEEKLY每周")
            SubscriptionMode mode,
            @Schema(description = "读取到的偏好版本，首次0，精确十进制字符串")
            String expectedVersion
    ) {
    }

    @Schema(name = "KnowledgeCandidatesRequest", description = "按当前可发现范围分页选择订阅目标")
    public record Candidates(
            @Schema(description = "必填目标种类：DOCUMENT文档、SPACE空间、TAG标签")
            SubscriptionKind kind,
            @Schema(description = "可选名称字面包含词，最多128个Unicode字符")
            String query,
            @Schema(description = "可选每页条数，1至100，默认20")
            Integer limit,
            @Schema(description = "可选同条件下一页游标，最多2731个ASCII字符")
            String cursor
    ) {
    }

    @Schema(name = "KnowledgeTargetRequest", description = "读取一个当前可订阅目标及本人关系版本")
    public record Target(
            @Schema(description = "DOCUMENT文档、SPACE空间、TAG标签")
            SubscriptionKind kind,
            @Schema(description = "目标资源正整数ID的精确十进制字符串")
            String targetId
    ) {
    }

    @Schema(name = "KnowledgeReceiptRequest", description = "只读核对本人原请求的回执，查无不代表未执行")
    public record ReceiptQuery(
            @Schema(description = "原请求标识，1至128个非控制Unicode字符")
            String requestId
    ) {
    }
}
