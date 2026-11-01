package com.zhishiku.kb.app.document.metadata;

import com.zhishiku.kb.app.auth.mapper.IdentityAuditMapper;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataAuditTest {
    @Test
    void appendFailureIsNotReinterpretedAsSuccessfulReplay() {
        var mapper = mock(IdentityAuditMapper.class);
        when(mapper.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("测试失败"));
        var audit = new IdentityAuditService(mapper);
        assertThatThrownBy(() -> audit.appendSuccess(AuditScope.DOCUMENT_METADATA_UPDATE, "id", 1, 2,
                "update", "{}", "{}")) .isInstanceOf(DataIntegrityViolationException.class);
        verify(mapper, never()).findByRequest(any(), any());
    }
}
