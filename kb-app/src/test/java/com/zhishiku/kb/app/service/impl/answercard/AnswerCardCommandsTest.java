package com.zhishiku.kb.app.service.impl.answercard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.domain.answercard.AnswerCardCommand;
import com.zhishiku.kb.app.enums.answercard.AnswerCardOperation;
import com.zhishiku.kb.app.mapper.answercard.AnswerCardCommandMapper;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnswerCardCommandsTest {
    private final AnswerCardCommandMapper mapper = mock(AnswerCardCommandMapper.class);
    private final AnswerCardCommands commands = new AnswerCardCommands(mapper, new ObjectMapper());

    @Test
    void canonicalObjectOrderKeepsLargeIdentityExact() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("answer", "住宿标准");
        first.put("source", Map.of("id", Long.MAX_VALUE));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("source", Map.of("id", Long.MAX_VALUE));
        second.put("answer", "住宿标准");
        var a = commands.bind(AnswerCardOperation.ANSWER_CARD_SAVE, "r", 1, 2, Long.MAX_VALUE,
                4L, 5L, null, 8L, first);
        var b = commands.bind(AnswerCardOperation.ANSWER_CARD_SAVE, "r", 1, 2, Long.MAX_VALUE,
                4L, 5L, null, 8L, second);
        var other = commands.bind(AnswerCardOperation.ANSWER_CARD_SAVE, "r", 1, 2, Long.MAX_VALUE - 1,
                4L, 5L, null, 8L, first);
        assertThat(a.digest()).isEqualTo(b.digest()).isNotEqualTo(other.digest());
        assertThat(a.toString()).doesNotContain("住宿标准", a.digest(), "requestId");
    }

    @Test
    void operationActorAndPayloadCannotReuseReceipt() {
        var binding = commands.bind(AnswerCardOperation.ANSWER_CARD_APPROVE, "r", 7, 2, 3L, 4L, 5L, 6L, 8L, Map.of());
        var row = new AnswerCardCommand(binding.operation(), "r", 8, 2, 3L, 4L, 5L, 6L, 8L,
                1, binding.digest(), "{}", null);
        when(mapper.find(binding.operation().name(), "r")).thenReturn(row);
        assertThatThrownBy(() -> commands.lock(binding)).isInstanceOfSatisfying(AnswerCardFailure.class,
                failure -> assertThat(failure.reason()).isEqualTo("REQUEST_MISMATCH"));
        verify(mapper).lockRequest("answer-card:ANSWER_CARD_APPROVE:r");
    }

    @Test
    void unchangedBindingReturnsOriginalAndMissingIsOnlyMissing() {
        var binding = commands.bind(AnswerCardOperation.ANSWER_CARD_CREATE, "r", 1, 2,
                null, null, null, null, null, Map.of("question", "问题"));
        assertThat(commands.find(binding)).isNull();
        var row = new AnswerCardCommand(binding.operation(), "r", 1, 2, null, null, null, null, null,
                1, binding.digest(), "{\"cardId\":\"9223372036854775807\"}", null);
        when(mapper.find(binding.operation().name(), "r")).thenReturn(row);
        assertThat(commands.find(binding)).isSameAs(row);
        assertThat(commands.receipt(row, Map.class)).containsEntry("cardId", "9223372036854775807");
    }

    @Test
    void storageFailureCannotAcknowledgeReceipt() {
        var binding = commands.bind(AnswerCardOperation.ANSWER_CARD_CREATE, "r", 1, 2,
                null, null, null, null, null, Map.of());
        when(mapper.insert(any())).thenReturn(0);
        assertThatThrownBy(() -> commands.save(binding, Map.of("cardId", "4")))
                .isInstanceOf(AnswerCardFailure.class);
    }

    @Test
    void invalidRequestIdentityIsRejected() {
        assertThatThrownBy(() -> commands.bind(AnswerCardOperation.ANSWER_CARD_SAVE, "r\nsecret", 1, 2,
                3L, 4L, 5L, null, 0L, Map.of())).isInstanceOf(AnswerCardFailure.class);
    }

    @Test
    void requestIdentityPreservesWhitespaceAndUsesCodePointBoundaries() {
        var plain = commands.bind(AnswerCardOperation.ANSWER_CARD_CREATE, "r", 1, 2,
                null, null, null, null, null, Map.of());
        var spaced = commands.bind(AnswerCardOperation.ANSWER_CARD_CREATE, " r ", 1, 2,
                null, null, null, null, null, Map.of());
        assertThat(spaced.requestId()).isEqualTo(" r ").isNotEqualTo(plain.requestId());
        for (String request : new String[]{" ", "\u2003", "😀".repeat(128)}) {
            assertThat(commands.bind(AnswerCardOperation.ANSWER_CARD_CREATE, request, 1, 2,
                    null, null, null, null, null, Map.of()).requestId()).isEqualTo(request);
        }
        for (String request : new String[]{"", "😀".repeat(129), "\uD800"}) {
            assertThatThrownBy(() -> commands.bind(AnswerCardOperation.ANSWER_CARD_CREATE, request, 1, 2,
                    null, null, null, null, null, Map.of())).isInstanceOf(AnswerCardFailure.class);
        }
    }
}
