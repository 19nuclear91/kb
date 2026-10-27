package com.zhishiku.kb.app.notification.mapper;

import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationViews;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/** 本人通知读取；列表与计数共用同一集合式可见性谓词，先过滤再截取。 */
@Mapper
public interface UserNotificationMapper {
    List<NotificationRow> list(@Param("userId") long userId, @Param("types") List<String> types,
                               @Param("spaceIds") List<Long> spaceIds,
                               @Param("principalKeys") List<String> principalKeys,
                               @Param("clearance") short clearance,
                               @Param("beforeId") Long beforeId, @Param("limit") int limit);

    long countUnread(@Param("userId") long userId, @Param("types") List<String> types,
                     @Param("spaceIds") List<Long> spaceIds,
                     @Param("principalKeys") List<String> principalKeys,
                     @Param("clearance") short clearance);

    /** 只按本人归属幂等设置首次已读时间；不读取目标，不存在与他人通知均无返回行。 */
    NotificationViews.ReadReceipt markRead(@Param("userId") long userId,
                                           @Param("notificationId") UUID notificationId);
}
