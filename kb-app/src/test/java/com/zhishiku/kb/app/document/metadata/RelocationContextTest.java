package com.zhishiku.kb.app.document.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.metadata.mapper.MetadataMapper;
import com.zhishiku.kb.app.document.metadata.service.MetadataAccess;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.metadata.service.RelocationContextService;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelocationContextTest {
    @Test
    void managerGetsOnlyIdentityAndVersionWithoutView() throws Exception {
        var mapper = mock(MetadataMapper.class);
        var access = mock(MetadataAccess.class);
        var service = new RelocationContextService(mapper, access);
        when(mapper.document(9)).thenReturn(DocumentEntity.builder().id(9L).spaceId(2L).metadataVersion(7L)
                .title("private").summary("private").status("archived").build());
        var result = service.get(9);
        var json = new ObjectMapper().valueToTree(result);
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.get("metadataVersion").longValue()).isEqualTo(7);
        verify(access, never()).document(anyLong(), anyBoolean());
        when(access.manager(2)).thenThrow(new MetadataException(MetadataException.Reason.NOT_FOUND));
        assertThatThrownBy(() -> service.get(9)).isInstanceOf(MetadataException.class);
        assertThatThrownBy(() -> service.get(10)).isInstanceOf(MetadataException.class);
    }
}
