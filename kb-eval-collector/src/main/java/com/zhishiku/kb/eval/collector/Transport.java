package com.zhishiku.kb.eval.collector;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.Map;

interface Transport extends AutoCloseable {
    /**
     * 单次发送，不提供重试或重定向接口。
     * @param uri 固定身份或搜索路径
     * @param method GET 或 POST
     * @param headers 对应身份的认证与 JSON 协议头
     * @param body 请求字节，身份请求为空
     */
    record Request(URI uri, String method, Map<String, String> headers, byte[] body) {
        @Override
        public String toString() {
            return "Request[redacted]";
        }
    }

    /**
     * 将响应流所有权交给协议层，失败和成功均须关闭。
     * @param status HTTP 状态码
     * @param headers 保留重复值的响应头
     * @param body 尚未聚合的正文流
     */
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

    @Override
    default void close() {
    }
}
