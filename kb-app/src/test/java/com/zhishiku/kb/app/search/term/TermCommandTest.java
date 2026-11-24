package com.zhishiku.kb.app.search.term;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.search.term.mapper.TermMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TermCommandTest {
    private final TermMapper mapper = mock(TermMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final TermCommands commands = new TermCommands(mapper, json);

    @Test
    void aliasOrderAndUnicodeNormalizationAreEquivalentButDisplayAndKindChangesAreNot() {
        var input = createInput();
        var reverse = new ArrayList<>(input.aliases());
        Collections.reverse(reverse);
        var reordered = new TermInputs.TermWrite(input.canonicalName(), input.canonicalKey(), input.description(), reverse, null);
        var binding = commands.bind(TermRows.Operation.SEARCH_TERM_CREATE, "request", 7, null, null, input);
        assertThat(commands.bind(binding.operation(), "request", 7, null, null, reordered).digest()).isEqualTo(binding.digest());
        var changed = new TermInputs.TermWrite("办公自动化", "不可信内部键", "释义", List.of(
                new TermInputs.Alias("oa", "不可信内部键", TermRows.AliasKind.ABBREVIATION),
                new TermInputs.Alias("旧系统", "旧系统", TermRows.AliasKind.FORMER_NAME)), null);
        assertThat(commands.bind(binding.operation(), "request", 7, null, null, changed).digest()).isNotEqualTo(binding.digest());
        var changedKind = new TermInputs.TermWrite(input.canonicalName(), input.canonicalKey(), input.description(), List.of(
                new TermInputs.Alias("OA", "oa", TermRows.AliasKind.SYNONYM), input.aliases().getLast()), null);
        assertThat(commands.bind(binding.operation(), "request", 7, null, null, changedKind).digest()).isNotEqualTo(binding.digest());
        var nfc = new TermInputs.CorrectionWrite("É", "É", 1, null);
        var composed = new TermInputs.CorrectionWrite("\u00a0E\u0301\u3000", "不同内部键", 1, null);
        assertThat(commands.bind(TermRows.Operation.SEARCH_CORRECTION_CREATE, "request", 7, null, null, nfc).digest())
                .isEqualTo(commands.bind(TermRows.Operation.SEARCH_CORRECTION_CREATE, "request", 7, null, null, composed).digest());
    }

    @Test
    void domainWideRequestLockDoesNotLetChangedOperationTargetActorVersionOrPayloadEscape() throws Exception {
        var binding = updateBinding(7, 9, 4, "名称");
        when(mapper.command("request")).thenReturn(row(binding, new TermReceipt(TermRows.EntityKind.TERM, 9, 5, 10, false)));
        List<TermCommands.Binding> changed = List.of(updateBinding(8, 9, 4, "名称"), updateBinding(7, 10, 4, "名称"),
                updateBinding(7, 9, 5, "名称"), updateBinding(7, 9, 4, "改名"),
                commands.bind(TermRows.Operation.SEARCH_TERM_ENABLED, "request", 7, 9L, 4L, new TermInputs.EnabledWrite(false, 4)),
                commands.bind(TermRows.Operation.SEARCH_CORRECTION_UPDATE, "request", 7, 9L, 4L,
                        new TermInputs.CorrectionWrite("报消", "报消", 1, 4L)));
        for (var value : changed) {
            assertThatThrownBy(() -> commands.lock(value)).isInstanceOfSatisfying(TermException.class,
                    error -> assertThat(error.reason()).isEqualTo(TermException.Reason.REQUEST_MISMATCH));
        }
        var order = inOrder(mapper);
        for (int i = 0; i < changed.size(); i++) {
            order.verify(mapper).lockRequest("search-terminology:request");
            order.verify(mapper).command("request");
        }
        verify(mapper, never()).insertCommand(any());
        verify(mapper, never()).lockState();
    }

    @Test
    void matchingReplayReturnsFirstReceiptWithoutReadingAdvancedCurrentEntityOrIncrementingAnything() throws Exception {
        var binding = updateBinding(7, Long.MAX_VALUE, 4, "名称");
        var receipt = new TermReceipt(TermRows.EntityKind.TERM, Long.MAX_VALUE, 5, 10, false);
        var row = row(binding, receipt);
        when(mapper.command("request")).thenReturn(row);
        assertThat(commands.lock(binding)).isSameAs(row);
        assertThat(commands.receipt(row).asReplay()).isEqualTo(new TermReceipt(TermRows.EntityKind.TERM, Long.MAX_VALUE, 5, 10, true));
        verify(mapper, never()).term(anyLong());
        verify(mapper, never()).correction(anyLong());
        verify(mapper, never()).lockState();
        verify(mapper, never()).insertCommand(any());
    }

    @Test
    void incompleteBindingsAreConflictsRatherThanSuccessfulReplays() throws Exception {
        var binding = updateBinding(7, 9, 4, "名称");
        var good = row(binding, new TermReceipt(TermRows.EntityKind.TERM, 9, 5, 10, false));
        List<TermRows.Command> incomplete = List.of(
                new TermRows.Command("request", null, 7, TermRows.EntityKind.TERM, 9L, 4L, 1, binding.digest(), good.receipt(), null),
                new TermRows.Command("request", binding.operation(), 7, null, 9L, 4L, 1, binding.digest(), good.receipt(), null),
                new TermRows.Command("request", binding.operation(), 7, binding.targetKind(), null, 4L, 1, binding.digest(), good.receipt(), null),
                new TermRows.Command("request", binding.operation(), 7, binding.targetKind(), 9L, null, 1, binding.digest(), good.receipt(), null),
                new TermRows.Command("request", binding.operation(), 7, binding.targetKind(), 9L, 4L, 2, binding.digest(), good.receipt(), null),
                new TermRows.Command("request", binding.operation(), 7, binding.targetKind(), 9L, 4L, 1, null, good.receipt(), null));
        for (var row : incomplete) {
            when(mapper.command("request")).thenReturn(row);
            assertThatThrownBy(() -> commands.lock(binding)).isInstanceOfSatisfying(TermException.class,
                    error -> assertThat(error.reason()).isEqualTo(TermException.Reason.REQUEST_MISMATCH));
            assertThatThrownBy(() -> commands.receipt(row)).isInstanceOfSatisfying(TermException.class,
                    error -> assertThat(error.reason()).isEqualTo(TermException.Reason.REQUEST_MISMATCH));
        }
    }

    @Test
    void corruptIncompleteOversizedOrWrongReceiptNeverBecomesSuccess() throws Exception {
        var binding = updateBinding(7, 9, 4, "名称");
        String valid = json.writeValueAsString(new TermReceipt(TermRows.EntityKind.TERM, 9, 5, 10, false));
        List<String> broken = List.of("{}", "null", "{} {}", "{" + " ".repeat(4096),
                valid.replace("\"id\":\"9\"", "\"id\":9"), valid.replace("\"version\":\"5\"", "\"version\":\"6\""),
                valid.replace("\"id\":\"9\"", "\"id\":\"10\""), valid.replace("\"dictionaryRevision\":\"10\"", "\"dictionaryRevision\":\"0\""),
                valid.replace("false", "true"), valid.replace("TERM", "CORRECTION"),
                valid.replace("\"id\":\"9\"", "\"id\":\"9\",\"id\":\"9\""),
                valid.replace("}", ",\"query\":\"探针\"}"));
        for (String receipt : broken) {
            var row = new TermRows.Command(binding.requestId(), binding.operation(), binding.actorId(), binding.targetKind(),
                    binding.targetId(), binding.expectedVersion(), 1, binding.digest(), receipt, null);
            assertThatThrownBy(() -> commands.receipt(row)).isInstanceOfSatisfying(TermException.class,
                    error -> assertThat(error.reason()).isEqualTo(TermException.Reason.UNAVAILABLE));
        }
    }

    @Test
    void saveOnlyAppendsBoundFirstReceiptAndDoesNotStoreBusinessText() {
        var binding = commands.bind(TermRows.Operation.SEARCH_TERM_CREATE, " request ", 7, null, null, createInput());
        var receipt = new TermReceipt(TermRows.EntityKind.TERM, Long.MAX_VALUE, 0, Long.MAX_VALUE, false);
        when(mapper.insertCommand(any())).thenReturn(1);
        commands.save(binding, receipt);
        var captured = ArgumentCaptor.forClass(TermRows.Command.class);
        verify(mapper).insertCommand(captured.capture());
        assertThat(captured.getValue().requestId()).isEqualTo("request");
        assertThat(captured.getValue().targetId()).isNull();
        assertThat(captured.getValue().expectedVersion()).isNull();
        assertThat(commands.receipt(captured.getValue())).isEqualTo(receipt);
        assertThat(captured.getValue().inputDigest()).hasSize(64);
        var body = TermInput.body(new ByteArrayInputStream(captured.getValue().receipt().getBytes(StandardCharsets.UTF_8)));
        assertThat(body.properties()).extracting(entry -> entry.getKey()).containsExactlyInAnyOrder("kind", "id", "version", "dictionaryRevision", "replayed");
        assertThat(body.get("id").textValue()).isEqualTo(Long.toString(Long.MAX_VALUE));
    }

    @Test
    void invalidIntentAndInvalidFirstReceiptFailWithoutWriting() {
        for (String request : List.of("", "a".repeat(129), "a\nb", "\uD800")) {
            assertThatThrownBy(() -> commands.bind(TermRows.Operation.SEARCH_TERM_CREATE, request, 7, null, null, createInput()))
                    .isInstanceOf(TermException.class);
        }
        assertThatThrownBy(() -> commands.bind(TermRows.Operation.SEARCH_TERM_UPDATE, "request", 7, null, 4L, createInput())).isInstanceOf(TermException.class);
        assertThatThrownBy(() -> commands.bind(TermRows.Operation.SEARCH_TERM_CREATE, "request", 0, null, null, createInput())).isInstanceOf(TermException.class);
        assertThatThrownBy(() -> commands.bind(TermRows.Operation.SEARCH_TERM_CREATE, "request", 7, 1L, null, createInput())).isInstanceOf(TermException.class);
        assertThatThrownBy(() -> commands.bind(TermRows.Operation.SEARCH_TERM_CREATE, "request", 7, null, null, new Object())).isInstanceOf(TermException.class);
        assertThatThrownBy(() -> commands.bind(TermRows.Operation.SEARCH_TERM_ENABLED, "request", 7, 1L, 4L, new TermInputs.EnabledWrite(true, 5)))
                .isInstanceOf(TermException.class);
        var binding = updateBinding(7, 9, 4, "名称");
        assertThatThrownBy(() -> commands.save(binding, new TermReceipt(TermRows.EntityKind.TERM, 9, 4, 10, false))).isInstanceOf(TermException.class);
        verify(mapper, never()).insertCommand(any());
        when(mapper.insertCommand(any())).thenReturn(0);
        assertThatThrownBy(() -> commands.save(binding, new TermReceipt(TermRows.EntityKind.TERM, 9, 5, 10, false)))
                .isInstanceOfSatisfying(TermException.class, error -> assertThat(error.reason()).isEqualTo(TermException.Reason.UNAVAILABLE));
    }

    private TermCommands.Binding updateBinding(long actor, long target, long version, String name) {
        return commands.bind(TermRows.Operation.SEARCH_TERM_UPDATE, "request", actor, target, version,
                new TermInputs.TermWrite(name, name, "释义", List.of(), version));
    }

    private TermInputs.TermWrite createInput() {
        return new TermInputs.TermWrite("办公自动化", "办公自动化", "释义", List.of(
                new TermInputs.Alias("OA", "oa", TermRows.AliasKind.ABBREVIATION),
                new TermInputs.Alias("旧系统", "旧系统", TermRows.AliasKind.FORMER_NAME)), null);
    }

    private TermRows.Command row(TermCommands.Binding binding, TermReceipt receipt) throws Exception {
        return new TermRows.Command(binding.requestId(), binding.operation(), binding.actorId(), binding.targetKind(), binding.targetId(),
                binding.expectedVersion(), 1, binding.digest(), json.writeValueAsString(receipt), OffsetDateTime.parse("2026-11-24T00:00:00Z"));
    }
}
