package com.zhishiku.kb.app.notification.mapper;

import com.zhishiku.kb.app.notification.model.ClaimedEvent;
import com.zhishiku.kb.app.notification.model.NotificationBacklog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/**
 * 派生进度与通知写入；只读取协作事实表，从不修改源事件、收件人意图或源业务。
 * 租约时间一律使用数据库时钟，调用方只传秒数。
 */
@Mapper
public interface NotificationDerivationMapper {
    /** 反连接发现尚无 projection 的事件并幂等登记，不依赖全局高水位。 */
    int discover(@Param("limit") int limit);

    /** 领取到期且未被他人持有有效租约的工作，推进 claim token。 */
    List<ClaimedEvent> claim(@Param("owner") String owner, @Param("limit") int limit,
                             @Param("leaseSeconds") int leaseSeconds);

    /** 源事务记录的提醒意图，按用户 ID 升序且排除事件操作者自身。 */
    List<Long> recipients(@Param("eventId") UUID eventId, @Param("afterUserId") long afterUserId,
                          @Param("limit") int limit);

    /** 推进已提交游标并续租；token 或持有者不匹配时为 0。 */
    int advance(@Param("eventId") UUID eventId, @Param("owner") String owner, @Param("token") long token,
                @Param("cursor") long cursor, @Param("leaseSeconds") int leaseSeconds);

    /** 唯一键冲突不刷新通知时间或 readAt。 */
    int deliver(@Param("eventId") UUID eventId, @Param("type") String type,
                @Param("recipients") List<Long> recipients);

    int complete(@Param("eventId") UUID eventId, @Param("owner") String owner, @Param("token") long token);

    /** 达到单轮处理上限后放回队列，保留已提交游标。 */
    int release(@Param("eventId") UUID eventId, @Param("owner") String owner, @Param("token") long token);

    /** 释放租约并按退避重新排期；status 为 PENDING 或 WAITING_SOURCE。 */
    int postpone(@Param("eventId") UUID eventId, @Param("owner") String owner, @Param("token") long token,
                 @Param("status") String status, @Param("errorClass") String errorClass,
                 @Param("delaySeconds") long delaySeconds);

    NotificationBacklog backlog();
}
