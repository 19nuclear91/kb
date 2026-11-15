package com.zhishiku.kb.app.document.stewardship;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.document.applicability.mapper.ValidityCommandMapper;
import com.zhishiku.kb.app.document.applicability.model.ValidityCommandRow;
import com.zhishiku.kb.app.document.applicability.service.BusinessDate;
import com.zhishiku.kb.app.document.applicability.service.ValidityCommands;
import com.zhishiku.kb.app.document.stewardship.mapper.StewardshipMapper;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipDocument;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipRow;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipAccess;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipWriteService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StewardshipFixture {
    final StewardshipMapper mapper = mock(StewardshipMapper.class);
    final StewardshipAccess access = mock(StewardshipAccess.class);
    final ValidityCommandMapper commandMapper = mock(ValidityCommandMapper.class);
    final IdentityAuditService audit = mock(IdentityAuditService.class);
    final BusinessDate date = mock(BusinessDate.class);
    final ValidityCommands commands = new ValidityCommands(commandMapper, new ObjectMapper());
    final AtomicReference<StewardshipRow> current = new AtomicReference<>();
    final Map<String, ValidityCommandRow> receipts = new HashMap<>();
    final StewardshipWriteService writes = new StewardshipWriteService(mapper, access, commands, audit, date);

    StewardshipFixture() {
        var document = new StewardshipDocument(10, 2, "active", false);
        when(mapper.shareDocument(10)).thenReturn(document);
        when(mapper.document(10)).thenReturn(document);
        when(access.manager(7, 10, true)).thenReturn(document);
        when(mapper.get(10)).thenAnswer(call -> current.get());
        when(mapper.lock(10)).thenAnswer(call -> current.get());
        when(mapper.eligible(10, 7)).thenReturn(true);
        when(mapper.eligible(10, 8)).thenReturn(true);
        when(mapper.eligible(10, 9)).thenReturn(true);
        when(date.today()).thenReturn(LocalDate.of(2026, 11, 30));
        when(mapper.insert(any())).thenAnswer(call -> save(call.getArgument(0)));
        when(mapper.update(any())).thenAnswer(call -> save(call.getArgument(0)));
        when(commandMapper.get(anyString(), anyString())).thenAnswer(call -> receipts.get(call.getArgument(0) + ":" + call.getArgument(1)));
        when(commandMapper.insert(any())).thenAnswer(call -> {
            ValidityCommandRow row = call.getArgument(0);
            receipts.put(row.operationScope() + ":" + row.requestId(), row);
            return 1;
        });
    }

    private int save(StewardshipRow row) {
        current.set(new StewardshipRow(row.documentId(), row.responsibleUserId(), row.cycleMonths(), row.firstAssignedOn(),
                row.lastConfirmedOn(), row.lastConfirmedBy(), row.dueDate(), row.stewardshipVersion(), OffsetDateTime.parse("2026-11-30T00:00:00Z")));
        return 1;
    }
}
