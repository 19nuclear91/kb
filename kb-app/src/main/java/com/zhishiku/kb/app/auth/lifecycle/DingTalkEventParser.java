package com.zhishiku.kb.app.auth.lifecycle;

import com.dingtalk.open.app.api.message.GenericOpenDingTalkEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

@Component
public class DingTalkEventParser {
    private static final Set<String> IGNORED = Set.of("user_add_org", "user_modify_org", "org_dept_create",
            "org_dept_modify", "org_dept_remove", "org_change", "org_remove");
    private final DingTalkLifecycleSettings settings;

    public DingTalkEventParser(DingTalkLifecycleSettings settings) {
        this.settings = settings;
    }

    public LifecycleIncomingEvent parse(GenericOpenDingTalkEvent event) {
        settings.requireConfigured();
        if (!settings.enterpriseId().equals(event.getEventCorpId())
                || !settings.unifiedAppId().equals(event.getEventUnifiedAppId())) {
            throw new DingTalkInputRejected(DingTalkInputRejected.Reason.SCOPE_MISMATCH);
        }
        if (event.getEventType() == null || !("user_leave_org".equals(event.getEventType()) || IGNORED.contains(event.getEventType()))) {
            throw new DingTalkInputRejected(DingTalkInputRejected.Reason.UNSUPPORTED_EVENT);
        }
        if (event.getEventId() == null || event.getEventId().isBlank() || event.getEventId().length() > 128
                || event.getEventBornTime() == null || event.getEventBornTime() <= 0
                || event.getEventBornTime() > 253402300799000L || event.getData() == null) {
            throw new DingTalkInputRejected(DingTalkInputRejected.Reason.INVALID_EVENT);
        }
        var employees = new TreeSet<String>();
        var payload = new TreeMap<String, Object>(event.getData());
        if ("user_leave_org".equals(event.getEventType())) {
            Object raw = event.getData().get("userId");
            if (!(raw instanceof java.util.List<?> ids) || ids.isEmpty()) {
                throw new DingTalkInputRejected(DingTalkInputRejected.Reason.INVALID_EVENT);
            }
            if (ids.size() > settings.maxEmployees()) {
                throw new DingTalkInputRejected(DingTalkInputRejected.Reason.EMPLOYEE_LIMIT);
            }
            for (Object id : ids) {
                if (!(id instanceof String value) || value.isBlank() || value.length() > 128
                        || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl)) {
                    throw new DingTalkInputRejected(DingTalkInputRejected.Reason.INVALID_EVENT);
                }
                employees.add(value);
            }
            payload.put("userId", employees.stream().toList());
        }
        String digest = LifecycleSupport.digest(Map.of("corp", event.getEventCorpId(), "app", event.getEventUnifiedAppId(),
                "type", event.getEventType(), "born", event.getEventBornTime(), "data", payload));
        return new LifecycleIncomingEvent(settings.sourceScope(), event.getEventId(), event.getEventType(),
                settings.enterpriseId(), settings.connectionId(), Instant.ofEpochMilli(event.getEventBornTime()),
                digest, employees.stream().toList());
    }
}
