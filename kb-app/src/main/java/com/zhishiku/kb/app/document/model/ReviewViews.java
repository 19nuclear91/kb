package com.zhishiku.kb.app.document.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;

import java.util.List;
import java.util.UUID;

/** 审核查询专用投影，与成功命令回执分离。 */
public final class ReviewViews {
    private ReviewViews() {
    }

    /**
     * 当前最小版本状态，不含文件、正文或人员名单。
     * @param documentId 文档标识
     * @param versionId 精确版本标识
     * @param versionNo 本人或当前可读版本的序号
     * @param stateVersion 当前并发版本
     * @param indexStatus 索引状态
     * @param policy 固定策略
     * @param publicationEvidence 发布证据
     * @param reviewState 本轮审核状态或 DRAFT
     * @param reviewId 当前轮次标识
     * @param roundNo 当前轮次序号
     * @param currentVersionId 仅有阅读资格时返回的当前发布版本
     * @param waitingReason 固定等待原因
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record State(@JsonSerialize(using = ToStringSerializer.class) long documentId,
                        @JsonSerialize(using = ToStringSerializer.class) long versionId, int versionNo,
                        @JsonSerialize(using = ToStringSerializer.class) long stateVersion, String indexStatus,
                        PublicationPolicy policy, PublicationEvidence publicationEvidence, String reviewState,
                        UUID reviewId, Integer roundNo,
                        @JsonSerialize(using = ToStringSerializer.class) Long currentVersionId, String waitingReason) {
    }

    /**
     * 独立版本能力，客户端不能凭角色、所有者或旧回执补授。
     * @param canRead 当前版本可交互读取
     * @param canDownload 当前版本另有 download
     * @param canSubmit 可手动送审
     * @param canApprove 可通过并发布
     * @param canReject 可驳回
     * @param canWithdraw 可本人撤回
     * @param canRefresh 可显式刷新候选索引
     * @param canReadOpinions 可显式读取私有意见
     */
    public record Capabilities(boolean canRead, boolean canDownload, boolean canSubmit, boolean canApprove,
                               boolean canReject, boolean canWithdraw, boolean canRefresh, boolean canReadOpinions) {
    }

    /**
     * 当前状态及显式按需加载的私有轮次。
     * @param state 当前最小状态
     * @param capabilities 当次版本能力
     * @param rounds 已授权且显式请求的私有轮次
     * @param hasMore 是否有更多已授权私有轮次
     * @param nextCursor 下一页私有轮次游标
     */
    public record Status(State state, Capabilities capabilities, List<PrivateReviewView> rounds,
                         boolean hasMore, String nextCursor) {
    }

    /**
     * 工作台条目；本人列表不返回标题。
     * @param state 本人或当前合格待办的版本状态
     * @param canRead 当次是否可进入正文
     * @param title 仅合格待办返回的已授权标题
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(State state, boolean canRead, String title) {
    }

    /**
     * 过滤后分页，没有隐藏总数。
     * @param items 当次允许的条目
     * @param hasMore 是否有更多已授权条目
     * @param nextCursor 下一页位置，不是授权凭据
     */
    public record Page(List<Item> items, boolean hasMore, String nextCursor) {
    }
}
