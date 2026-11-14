package com.zhishiku.kb.app.knowledgegap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapInput;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 独立严格 JSON、真实字节上限和纯输入合同；不读取说明文档，不启动外部服务。 */
class KnowledgeGapInputTest {
    private static final String UUID_VALUE = "e4eb15dd-7a1c-4d12-91bf-5a1957d14b51";

    @ParameterizedTest(name = "{0} 拒绝未知/重复/尾随与非法根节点")
    @MethodSource("parsers")
    void everyParserRejectsUnknownDuplicateTrailingAndWrongRoot(Parser parser) {
        assertThat(parser.parse(parser.valid())).isNotNull();
        String firstKey = parser.valid().substring(1, parser.valid().indexOf(':'));
        for (String body : List.of(parser.valid().replaceFirst("\\}$", ",\"privateAnswer\":\"秘密答案\"}"),
                parser.valid().replaceFirst("\\}$", "," + firstKey + ":null}"), parser.valid() + " {}", "[]", "null", "true", "", "{]")) {
            assertStatus(400, () -> parser.parse(body));
        }
    }

    @ParameterizedTest(name = "{0} 按实际读取最多 64 KiB+1")
    @MethodSource("parsers")
    void byteLimitDoesNotDependOnDeclaredContentLength(Parser parser) {
        byte[] body = parser.valid().getBytes(StandardCharsets.UTF_8);
        String padded = parser.valid() + " ".repeat(KnowledgeGapInputs.MAX_BODY_BYTES - body.length);
        assertThat(parser.parse(padded)).isNotNull();
        var input = new CountingInput((padded + " ".repeat(1000)).getBytes(StandardCharsets.UTF_8));
        assertStatus(413, () -> parser.function().apply(input));
        assertThat(input.readBytes).isEqualTo(KnowledgeGapInputs.MAX_BODY_BYTES + 1);
    }

    @Test
    void identitiesVersionsAndLimitsAreIntegralAndExactBeyondJavascriptSafeInteger() {
        var value = KnowledgeGapInput.assignment(stream("{\"expectedVersion\":9007199254740993,\"assigneeUserId\":9007199254740994}"));
        assertThat(value.expectedVersion()).isEqualTo(9007199254740993L);
        assertThat(value.assigneeUserId()).isEqualTo(9007199254740994L);
        for (String number : List.of("1.0", "1e0", "\"1\"", "true", "null", "-1", "9223372036854775808")) {
            assertStatus(400, () -> KnowledgeGapInput.assignment(stream("{\"expectedVersion\":" + number + ",\"assigneeUserId\":8}")));
        }
        for (String number : List.of("0", "101", "1.0", "\"20\"", "null")) {
            assertStatus(400, () -> KnowledgeGapInput.search(stream("{\"limit\":" + number + "}")));
        }
    }

    @Test
    void textLengthsUseUnicodeCodePointsWithoutTruncationOrDamagedSurrogates() throws Exception {
        var json = new ObjectMapper();
        String emoji = "😀".repeat(4000);
        var solution = KnowledgeGapInput.solution(stream("{\"expectedVersion\":1,\"text\":" + json.writeValueAsString(" " + emoji + " ") + "}"));
        assertThat(solution.text()).isEqualTo(emoji);
        for (String text : List.of("😀".repeat(4001), "a\0b", " ")) {
            String body = "{\"expectedVersion\":1,\"text\":" + json.writeValueAsString(text) + "}";
            assertStatus(400, () -> KnowledgeGapInput.solution(stream(body)));
        }
        for (String escape : List.of("\\uD800", "\\uDC00")) {
            assertStatus(400, () -> KnowledgeGapInput.solution(stream("{\"expectedVersion\":1,\"text\":\"" + escape + "\"}")));
        }
        assertThat(KnowledgeGapInput.note(stream("{\"expectedVersion\":1,\"text\":\"" + "😀".repeat(2000) + "\"}")).text()).hasSize(4000);
        assertStatus(400, () -> KnowledgeGapInput.note(stream("{\"expectedVersion\":1,\"text\":\"" + "😀".repeat(2001) + "\"}")));
        assertThat(KnowledgeGapInput.search(stream("{\"nameQuery\":\"" + "😀".repeat(128) + "\"}")).nameQuery()).hasSize(256);
        assertStatus(400, () -> KnowledgeGapInput.search(stream("{\"nameQuery\":\"" + "😀".repeat(129) + "\"}")));
        assertStatus(400, () -> KnowledgeGapInput.search(stream("{\"cursor\":\"\\u0000\"}")));
    }

    @Test
    void incompleteReferenceAndNonCanonicalUuidAreRejected() {
        for (String fields : List.of("\"documentId\":21", "\"versionId\":31", "\"documentId\":0,\"versionId\":31")) {
            assertStatus(400, () -> KnowledgeGapInput.solution(stream("{\"expectedVersion\":1,\"text\":\"说明\"," + fields + "}")));
        }
        for (String value : List.of("1-1-1-1-1", " " + UUID_VALUE, UUID_VALUE + " ", "not-a-uuid")) {
            assertStatus(400, () -> KnowledgeGapInput.confirmation(stream("{\"expectedVersion\":2,\"solutionId\":\"" + value + "\"}")));
        }
    }

    @Test
    void malformedUtf8AndDeepPayloadFailWithSafeInputError() {
        byte[] prefix = "{\"expectedVersion\":1,\"text\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[prefix.length + 2 + suffix.length];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        bytes[prefix.length] = (byte) 0xc3;
        bytes[prefix.length + 1] = (byte) 0x28;
        System.arraycopy(suffix, 0, bytes, prefix.length + 2, suffix.length);
        assertStatus(400, () -> KnowledgeGapInput.note(new ByteArrayInputStream(bytes)));
        assertStatus(400, () -> KnowledgeGapInput.search(stream("{\"nameQuery\":{\"secret\":{\"body\":\"秘密\"}}}")));
    }

    @Test
    void requestIdMustBeOneValidHeaderAndCannotCarryControlOrInvalidUnicode() {
        var request = new MockHttpServletRequest();
        assertStatus(400, () -> KnowledgeGapInput.requestId(request));
        request.addHeader("X-Request-Id", "one");
        assertThat(KnowledgeGapInput.requestId(request)).isEqualTo("one");
        request.addHeader("X-Request-Id", "two");
        assertStatus(400, () -> KnowledgeGapInput.requestId(request));
        for (String id : List.of("", "x".repeat(129), "x\ny", "\uD800", "a\0b")) {
            var invalid = new MockHttpServletRequest();
            invalid.addHeader("X-Request-Id", id);
            assertStatus(400, () -> KnowledgeGapInput.requestId(invalid));
        }
        var query = new MockHttpServletRequest();
        query.setQueryString("nameQuery=秘密名称");
        assertStatus(400, () -> KnowledgeGapInput.noQuery(query));
    }

    private static Stream<Parser> parsers() {
        return Stream.of(new Parser("create", KnowledgeGapInput::create,
                        "{\"category\":\"OTHER\",\"description\":\"分享描述\",\"source\":\"MANUAL\",\"shareAcknowledged\":true}"),
                new Parser("assignment", KnowledgeGapInput::assignment, "{\"expectedVersion\":0,\"assigneeUserId\":8}"),
                new Parser("note", KnowledgeGapInput::note, "{\"expectedVersion\":1,\"text\":\"说明\"}"),
                new Parser("reason", KnowledgeGapInput::reason, "{\"expectedVersion\":1,\"reason\":\"原因\"}"),
                new Parser("solution", KnowledgeGapInput::solution, "{\"expectedVersion\":1,\"text\":\"方案\",\"documentId\":21,\"versionId\":31}"),
                new Parser("confirmation", KnowledgeGapInput::confirmation, "{\"expectedVersion\":2,\"solutionId\":\"" + UUID_VALUE + "\"}"),
                new Parser("rejection", KnowledgeGapInput::rejection, "{\"expectedVersion\":2,\"solutionId\":\"" + UUID_VALUE + "\",\"reason\":\"仍未解决\"}"),
                new Parser("search", KnowledgeGapInput::search, "{\"nameQuery\":\"资料\",\"limit\":20}"));
    }

    private record Parser(String name, Function<InputStream, ?> function, String valid) {
        Object parse(String body) {
            return function.apply(stream(body));
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final class CountingInput extends InputStream {
        private final ByteArrayInputStream delegate;
        int readBytes;

        CountingInput(byte[] bytes) {
            delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() {
            int value = delegate.read();
            if (value >= 0) {
                readBytes++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            int count = delegate.read(bytes, offset, length);
            readBytes += Math.max(0, count);
            return count;
        }
    }

    private static InputStream stream(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertStatus(int expected, Runnable action) {
        assertThatThrownBy(action::run).satisfies(error -> {
            int status = error instanceof ApiException api ? api.httpStatus() : ((QueryException) error).status();
            assertThat(status).isEqualTo(expected);
            assertThat(error.getMessage()).doesNotContain("秘密");
        });
    }
}
