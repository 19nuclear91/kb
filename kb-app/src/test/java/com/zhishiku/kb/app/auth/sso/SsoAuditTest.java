package com.zhishiku.kb.app.auth.sso;

import com.zhishiku.kb.app.auth.mapper.IdentityAuditMapper;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SsoAuditTest {
    @Test
    void terminalUsesFixedScopesNullUnknownTargetAndServerFlowDeduplication() {
        var mapper = mock(IdentityAuditMapper.class);
        Map<String, IdentityAuditRecord> records = new HashMap<>();
        when(mapper.findByRequest(anyString(), anyString())).thenAnswer(i -> records.get(i.getArgument(0) + ":" + i.getArgument(1)));
        when(mapper.insert(any(), anyString(), anyString(), any(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any()))
                .thenAnswer(i -> {
                    records.put(i.getArgument(1) + ":" + i.getArgument(2), new IdentityAuditRecord(1L, i.getArgument(0),
                            i.getArgument(1), i.getArgument(2), i.getArgument(3), i.getArgument(4), i.getArgument(5),
                            i.getArgument(6), i.getArgument(7), i.getArgument(8), i.getArgument(9), i.getArgument(10)
                            , i.getArgument(11)));
                    return 1;
                });
        var audit = new SsoAudit(new IdentityAuditService(mapper));
        String flow = UUID.randomUUID().toString();
        audit.terminal(flow, "LOGIN", "feishu", null, false, "SSO_BINDING_REQUIRED");
        audit.terminal(flow, "LOGIN", "feishu", null, false, "SSO_BINDING_REQUIRED");
        verify(mapper, times(1)).insert(any(UUID.class), eq(AuditScope.SSO_LOGIN.name()), eq(flow), isNull(), eq(
                "user"), isNull(),
                eq("login"), eq("FAILED"), eq("{}"), eq("{\"platform\":\"feishu\"," +
                        "\"resultCode\":\"BINDING_REQUIRED\"}"), eq("BINDING_REQUIRED"), any(Instant.class));
        audit.terminal(UUID.randomUUID().toString(), "BIND", "wecom", 7L, true, "ALREADY_BOUND");
        verify(mapper).insert(any(UUID.class), eq("SSO_BIND"), anyString(), eq(7L), eq("user"), eq(7L), eq("bind"),
                eq("SUCCESS"),
                eq("{}"), eq("{\"platform\":\"wecom\",\"resultCode\":\"ALREADY_BOUND\",\"bound\":true}"), isNull(),
                any(Instant.class));
    }
}
