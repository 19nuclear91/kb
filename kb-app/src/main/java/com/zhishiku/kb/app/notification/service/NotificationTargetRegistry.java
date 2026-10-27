package com.zhishiku.kb.app.notification.service;

import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationTarget;
import com.zhishiku.kb.app.notification.model.NotificationType;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 已安装的目标解析器；派生与读取共用，未注册类型安全等待且不可见。 */
@Component
public class NotificationTargetRegistry {
    private final Map<NotificationType, NotificationTargetResolver> resolvers;

    public NotificationTargetRegistry(List<NotificationTargetResolver> installed) {
        Map<NotificationType, NotificationTargetResolver> map = new EnumMap<>(NotificationType.class);
        for (var resolver : installed) {
            for (var type : resolver.types()) {
                if (map.put(type, resolver) != null) throw new IllegalStateException("Duplicate notification resolver");
            }
        }
        resolvers = Collections.unmodifiableMap(map);
    }

    public boolean supports(NotificationType type) {
        return resolvers.containsKey(type);
    }

    /** 读取谓词使用的固定类型名称，按枚举顺序稳定。 */
    public List<String> readableTypes() {
        return Arrays.stream(NotificationType.values()).filter(resolvers::containsKey).map(Enum::name).toList();
    }

    public Optional<NotificationTarget> resolve(NotificationRow row) {
        var resolver = resolvers.get(NotificationType.valueOf(row.type()));
        if (resolver == null) throw new IllegalStateException("Notification type is not readable");
        return resolver.resolve(row);
    }
}
