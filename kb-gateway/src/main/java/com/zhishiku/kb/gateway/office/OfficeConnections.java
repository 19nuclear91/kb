package com.zhishiku.kb.gateway.office;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** 本机有界传输绑定。多副本网关需按 /s/{sid} 粘滞；丢失绑定只会拒绝并要求重新打开。 */
final class OfficeConnections {
    private record Binding(String engineId, long expiresAt, boolean socket) {
    }

    private final Map<String, Binding> bindings = new HashMap<>();
    private final int limit;
    private final Clock clock;

    OfficeConnections(int limit, Clock clock) {
        this.limit = limit;
        this.clock = clock;
    }

    synchronized void enter(String id, String engineId, long expiresAt, boolean socket) {
        bindings.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= clock.millis());
        Binding old = bindings.get(id);
        if (engineId == null) {
            if (old != null || bindings.size() >= limit) {
                throw new OfficeProxyFailure(429);
            }
            bindings.put(id, new Binding(null, expiresAt, socket));
        } else {
            if (old == null || !engineId.equals(old.engineId()) || old.socket()) {
                throw new OfficeProxyFailure(404);
            }
            bindings.put(id, new Binding(engineId, expiresAt, socket));
        }
    }

    synchronized void opened(String id, String engineId) {
        Binding old = bindings.get(id);
        if (old == null || old.engineId() != null || !engineId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new OfficeProxyFailure(404);
        }
        bindings.put(id, new Binding(engineId, old.expiresAt(), old.socket()));
    }

    synchronized void renew(String id, long expiresAt) {
        Binding old = bindings.get(id);
        if (old != null) {
            bindings.put(id, new Binding(old.engineId(), expiresAt, old.socket()));
        }
    }

    synchronized void remove(String id) {
        bindings.remove(id);
    }
}
