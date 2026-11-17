package com.zhishiku.kb.answereval;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SseAnswersTest {
    @Test
    void decodesCommentsUnicodeAndExactLongIdentity() throws IOException {
        String stream = ": keep-alive\r\n\r\n" + event("citation", Map.of("documentId", Long.MAX_VALUE,
                "versionId", 2, "chunkId", 3, "contextTruncated", true))
                + event("delta", Map.of("text", "答案🧾")) + done();
        var result = parse(stream);
        assertThat(result.answer()).isEqualTo("答案🧾");
        assertThat(result.conversationId()).isEqualTo(Long.MAX_VALUE);
        assertThat(result.citations().getFirst().source().docId()).isEqualTo(Long.MAX_VALUE);
        assertThat(result.error()).isNull();
    }

    @Test
    void rejectsEofDuplicateDoneDataAfterDoneAndWrongIdentity() {
        for (String stream : List.of(event("delta", Map.of("text", "partial")), done() + done(),
                done() + event("delta", Map.of("text", "late")),
                event("done", Map.of("conversationId", 1, "turnId", DataFixtures.UUID, "status", "COMPLETED")))) {
            assertThatThrownBy(() -> parse(stream)).isInstanceOf(EvaluationFailure.class);
        }
    }

    @Test
    void errorsRemainIncompleteAndVirtualDeadlineStopsParsing() throws IOException {
        var failed = parse(event("error", Map.of("conversationId", Long.MAX_VALUE, "turnId", DataFixtures.UUID,
                "code", "SEARCH_UNAVAILABLE", "message", "PRIVATE_ERROR")));
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.answer()).isNull();
        AtomicLong clock = new AtomicLong();
        Deadline deadline = new Deadline(clock::get, 1);
        clock.set(1_000_000);
        assertThatThrownBy(() -> SseAnswers.parse(done().getBytes(StandardCharsets.UTF_8), Long.MAX_VALUE,
                DataFixtures.UUID, deadline)).isInstanceOf(EvaluationFailure.class).hasMessage("ANSWER_EVAL_ERROR DEADLINE");
    }

    static String event(String type, Object value) {
        return "event: " + type + "\ndata: " + new String(StrictJson.bytes(value), StandardCharsets.UTF_8) + "\n\n";
    }

    static String done() {
        return event("done", Map.of("conversationId", Long.MAX_VALUE, "turnId", DataFixtures.UUID, "status", "COMPLETED"));
    }

    private BusinessClient.Turn parse(String value) throws IOException {
        return SseAnswers.parse(value.getBytes(StandardCharsets.UTF_8), Long.MAX_VALUE, DataFixtures.UUID,
                new Deadline(System::nanoTime, 1000));
    }
}
