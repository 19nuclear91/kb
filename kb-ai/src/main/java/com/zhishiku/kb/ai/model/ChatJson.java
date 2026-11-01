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
        if (value == null) {
            throw new ChatModelFailure(ChatModelFailure.Kind.PROTOCOL_INVALID);
        }
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
            } else if (Character.isLowSurrogate(c)) {
                throw new ChatModelFailure(ChatModelFailure.Kind.PROTOCOL_INVALID);
            }
        }
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
