package com.zhishiku.kb.app.auth.lifecycle;

/** 返回时必须已经提交可靠接收事务，事务中不得访问供应商。 */
@FunctionalInterface
public interface LifecycleEventConsumer {
    void receive(LifecycleIncomingEvent event);
}
