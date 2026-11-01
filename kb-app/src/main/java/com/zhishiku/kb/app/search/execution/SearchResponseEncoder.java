package com.zhishiku.kb.app.search.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.common.api.R;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/** 在搜索预算内完成有界 JSON 编码，HTTP 层只发送完整字节。 */
@Component
public class SearchResponseEncoder {
    private final ObjectMapper json;
    private final int maximum;

    public SearchResponseEncoder(ObjectMapper json, SearchLimitsProperties limits) {
        this.json = json;
        this.maximum = limits.maxResponseBytes();
    }

    public byte[] encode(List<ChunkHit> hits) {
        return encodeValue(hits);
    }

    public byte[] encodeValue(Object value) {
        var output = new ByteArrayOutputStream() {
            private void check(int size) {
                var context = SearchContext.current();
                if (context != null) {
                    context.check();
                }
                if (size > maximum - count) {
                    throw new SearchFailure(SearchFailure.Kind.RESPONSE_TOO_LARGE);
                }
            }

            @Override
            public synchronized void write(byte[] bytes, int offset, int length) {
                check(length);
                super.write(bytes, offset, length);
            }

            @Override
            public synchronized void write(int value) {
                check(1);
                super.write(value);
            }
        };
        try {
            json.writeValue(output, R.ok(value));
            return output.toByteArray();
        } catch (IOException failure) {
            Throwable cause = failure;
            while (cause != null) {
                if (cause instanceof SearchFailure known) {
                    throw known;
                }
                cause = cause.getCause();
            }
            throw new IllegalStateException("搜索结果编码失败");
        }
    }
}
