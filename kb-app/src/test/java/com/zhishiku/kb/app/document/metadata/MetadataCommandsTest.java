package com.zhishiku.kb.app.document.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.document.metadata.mapper.MetadataMapper;
import com.zhishiku.kb.app.document.metadata.model.MetadataCommandRow;
import com.zhishiku.kb.app.document.metadata.model.MetadataViews;
import com.zhishiku.kb.app.document.metadata.service.MetadataCommands;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.metadata.web.MetadataInput;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataCommandsTest {
    private final MetadataMapper mapper = mock(MetadataMapper.class);
    private final MetadataCommands commands = new MetadataCommands(mapper, new ObjectMapper().findAndRegisterModules());

    @Test
    void lostResponseReplaysOriginalReceiptAndBindingRejectsChangedIntents() {
        var stored = new AtomicReference<MetadataCommandRow>();
        when(mapper.command("DOCUMENT_CATEGORY_CREATE", "request")).thenAnswer(call -> stored.get());
        when(mapper.insertCommand(any())).thenAnswer(call -> {
            stored.set(call.getArgument(0));
            return 1;
        });
        var binding = commands.bind(AuditScope.DOCUMENT_CATEGORY_CREATE, "request", 7, null, 2L, null, 0L, "目录");
        assertThat(commands.lock(binding)).isNull();
        commands.save(binding, new MetadataViews.CategoryReceipt(91, 1, false));
        var replay = commands.receipt(commands.lock(binding), MetadataViews.CategoryReceipt.class);
        assertThat(replay.categoryId()).isEqualTo(91);
        verify(mapper, times(1)).insertCommand(any());
        var order = inOrder(mapper);
        order.verify(mapper).lockRequest("document-metadata:DOCUMENT_CATEGORY_CREATE:request");
        order.verify(mapper).command("DOCUMENT_CATEGORY_CREATE", "request");
        for (var changed : List.of(
                commands.bind(binding.scope(), "request", 8, null, 2L, null, 0L, "目录"),
                commands.bind(binding.scope(), "request", 7, 9L, 2L, null, 0L, "目录"),
                commands.bind(binding.scope(), "request", 7, null, 3L, null, 0L, "目录"),
                commands.bind(binding.scope(), "request", 7, null, 2L, null, 1L, "目录"),
                commands.bind(binding.scope(), "request", 7, null, 2L, null, 0L, "其他"))) {
            assertThatThrownBy(() -> commands.lock(changed)).isInstanceOf(MetadataException.class);
        }
    }

    @Test
    void normalizedTagSetsProduceSameBinding() {
        String raw = "{\"title\":\"标题\",\"summary\":null,\"categoryId\":null,\"tagIds\":[7,3],\"expectedMetadataVersion\":2}";
        var a = MetadataInput.update(MetadataInput.body(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), 65536));
        var b = MetadataInput.update(MetadataInput.body(new ByteArrayInputStream(raw.replace("7,3", "3,7")
                .getBytes(StandardCharsets.UTF_8)), 65536));
        assertThat(commands.bind(AuditScope.DOCUMENT_METADATA_UPDATE, "id", 7, 9L, null, 9L, 2L, a))
                .isEqualTo(commands.bind(AuditScope.DOCUMENT_METADATA_UPDATE, "id", 7, 9L, null, 9L, 2L, b));
    }
}
