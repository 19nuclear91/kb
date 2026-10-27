package com.zhishiku.kb.app.notification.model;

import java.util.UUID;

/**
 * 已领取的派生工作；claim token 是本次执行的唯一推进凭据。
 *
 * @param eventId 源协作事件
 * @param kind 源事件种类原文，未知种类不得当作成功空收件人
 * @param claimToken 单调领取代次
 * @param recipientCursor 已提交的收件人位置
 * @param attempts 已退避次数
 * @param takeover 是否接管了过期租约
 */
public record ClaimedEvent(UUID eventId, String kind, long claimToken, long recipientCursor, int attempts,
                           boolean takeover) {
}
