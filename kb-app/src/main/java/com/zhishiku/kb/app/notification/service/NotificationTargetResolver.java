package com.zhishiku.kb.app.notification.service;

import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationTarget;
import com.zhishiku.kb.app.notification.model.NotificationType;

import java.util.Optional;
import java.util.Set;

/**
 * 固定通知类型的目标解析扩展点。
 *
 * <p>实现只依据源模块 service 契约或同源 SQL 授权片段给出的事实判定，不调用其他模块 Mapper。
 * 类型未注册解析器时，派生保持 WAITING_SOURCE，读取时不可见。
 */
public interface NotificationTargetResolver {
    /** 本解析器负责的固定类型；不同解析器之间不得重叠。 */
    Set<NotificationType> types();

    /**
     * 按当前事实复核可见性并给出固定目标。
     *
     * @return 当前不可见时为空；事实与类型不一致时抛出异常，不猜测目标
     */
    Optional<NotificationTarget> resolve(NotificationRow row);
}
