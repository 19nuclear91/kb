package com.zhishiku.kb.answereval;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.Map;

/** 单次传输接口，测试只注入内存替身。 */
public interface Transport extends AutoCloseable {
    record Request(URI uri, String method, Map<String, String> headers, byte[] body) {
        public Request {
            headers = Map.copyOf(headers);
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public String toString() {
            return "Request[redacted]";
        }
    }

    record Response(int status, HttpHeaders headers, InputStream body) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            body.close();
        }

        @Override
        public String toString() {
            return "Response[redacted]";
        }
    }

    Response send(Request request, Deadline deadline) throws IOException, InterruptedException;

    default DataModel.InvocationOrigin invocationOrigin() {
        return DataModel.InvocationOrigin.TEST_DOUBLE;
    }

    @Override
    default void close() {
    }
}
