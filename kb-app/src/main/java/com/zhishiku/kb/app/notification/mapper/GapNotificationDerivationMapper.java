package com.zhishiku.kb.app.notification.mapper;

import org.apache.ibatis.annotations.Mapper;

/** 相同派生进度合同的独立 SQL 适配器；只读问题无正文事实，写本来源 projection 和共享收件箱。 */
@Mapper
public interface GapNotificationDerivationMapper extends NotificationDerivationMapper {
}
