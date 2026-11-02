package com.zhishiku.kb.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** 编码过程中限制字节，避免先分配完整的超限请求。 */
final class ChatJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ChatJson() {
    }

    static byte[] encode(Object value, int maximum) {
        var output = new LimitedOutput(maximum);
        try {
            JSON.writeValue(output, value);
            return output.bytes.toByteArray();
        } catch (IOException | RuntimeException error) {
            throw new ChatModelFailure(output.exceeded ? ChatModelFailure.Kind.PAYLOAD_LIMIT
                    : ChatModelFailure.Kind.PROTOCOL_INVALID);
        }
    }

    static void text(String value) {
        utf8Length(value);
    }

    /** 验证 Unicode 并线性统计 UTF-8 字节，不分配正文的字节副本。 */
    static long utf8Length(String value) {
        if (value == null) {
            throw new ChatModelFailure(ChatModelFailure.Kind.PROTOCOL_INVALID);
        }
        long bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) {
                throw new ChatModelFailure(ChatModelFailure.Kind.PROTOCOL_INVALID);
            }
            if (Character.isHighSurrogate(c)) {
                i++;
                if (i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new ChatModelFailure(ChatModelFailure.Kind.PROTOCOL_INVALID);
                }
                bytes = Math.addExact(bytes, 4);
            } else if (Character.isLowSurrogate(c)) {
                throw new ChatModelFailure(ChatModelFailure.Kind.PROTOCOL_INVALID);
            } else {
                bytes = Math.addExact(bytes, c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3);
            }
        }
        return bytes;
    }

    private static final class LimitedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int maximum;
        private boolean exceeded;

        private LimitedOutput(int maximum) {
            this.maximum = maximum;
        }

        @Override
        public void write(int value) throws IOException {
            check(1);
            bytes.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            check(length);
            bytes.write(value, offset, length);
        }

        private void check(int length) throws IOException {
            if (length > maximum - bytes.size()) {
                exceeded = true;
                throw new IOException("PAYLOAD_LIMIT");
            }
        }
    }
}
