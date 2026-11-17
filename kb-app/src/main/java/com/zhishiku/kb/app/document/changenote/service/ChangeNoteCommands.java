package com.zhishiku.kb.app.document.changenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.document.changenote.mapper.ChangeNoteCommandMapper;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteCommand;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteInput;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteViews.Receipt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static com.zhishiku.kb.app.document.changenote.service.ChangeNoteException.Reason.REQUEST_MISMATCH;
import static com.zhishiku.kb.app.document.changenote.service.ChangeNoteException.Reason.UNAVAILABLE;

@Service
public class ChangeNoteCommands {
    private final ChangeNoteCommandMapper mapper;
    private final ObjectMapper json;

    public ChangeNoteCommands(ChangeNoteCommandMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json.copy().findAndRegisterModules().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public record Binding(String requestId, long actor, long document, long version, long expected, String digest) {
        public AuditScope scope() {
            return AuditScope.DOCUMENT_CHANGE_NOTE_UPDATE;
        }
    }

    public Binding bind(String requestId, long actor, long document, long version, ChangeNoteInput input) {
        if (actor <= 0 || document <= 0 || version <= 0 || input == null) {
            throw ChangeNoteInput.invalid();
        }
        return new Binding(ChangeNoteInput.requestId(requestId), actor, document, version, input.expectedNoteVersion(),
                digest(write(Arrays.asList(1, AuditScope.DOCUMENT_CHANGE_NOTE_UPDATE.name(), actor, document, version, input))));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ChangeNoteCommand lock(Binding binding) {
        mapper.lockRequest("document-change-note:" + binding.requestId());
        var row = mapper.get(binding.scope().name(), binding.requestId());
        if (row != null && (row.digestVersion() != 1 || row.actorUserId() != binding.actor()
                || row.documentId() != binding.document() || row.targetId() != binding.version()
                || row.expectedVersion() != binding.expected() || !row.inputDigest().equals(binding.digest()))) {
            throw new ChangeNoteException(REQUEST_MISMATCH);
        }
        return row;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(Binding binding, Receipt receipt) {
        if (mapper.insert(new ChangeNoteCommand(binding.scope().name(), binding.requestId(), binding.actor(), binding.version(),
                binding.document(), binding.expected(), 1, binding.digest(), write(receipt))) != 1) {
            throw new ChangeNoteException(UNAVAILABLE);
        }
    }

    public Receipt receipt(ChangeNoteCommand row) {
        try {
            return json.readValue(row.receipt(), Receipt.class);
        } catch (Exception failure) {
            throw new ChangeNoteException(UNAVAILABLE);
        }
    }

    public String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new ChangeNoteException(UNAVAILABLE);
        }
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 不可用");
        }
    }
}
