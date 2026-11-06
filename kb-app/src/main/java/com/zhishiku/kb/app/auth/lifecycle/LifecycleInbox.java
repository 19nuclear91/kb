package com.zhishiku.kb.app.auth.lifecycle;

/** 调用方在接收事务及统一管理锁内操作，不提供删除去重身份的入口。 */
public interface LifecycleInbox {
    LifecycleEventRecord findEvent(String sourceScope, String eventId);

    int insertEvent(LifecycleEventRecord event);

    int markEventConflict(long id);
}
