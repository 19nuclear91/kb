package com.zhishiku.kb.app.auth.lifecycle;

/** 映射唯一性判定所需的最小仓储接口；写入方须先持有统一管理锁。 */
public interface LifecycleAssignments {
    LifecycleAssignment findAssignment(long userId);

    LifecycleAssignment findEmployee(String source, String enterpriseId, String employeeId);
}
