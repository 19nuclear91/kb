package com.zhishiku.kb.eval.collector;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

import static com.zhishiku.kb.eval.collector.CollectionException.Code.PROTOCOL;

/** 期限覆盖响应头、流读取及解析；所有非成功响应均停止，不重放。 */
final class Protocol {
    /**
     * 最终排名中的一个完整分块身份。
     * @param docId 文档正整数 ID
     * @param versionId 版本正整数 ID
     * @param chunkId 分块正整数 ID
     */
    record Hit(long docId, long versionId, long chunkId) {
    }

    /**
     * 完整校验通过的搜索响应。
     * @param hits 保持原数组顺序的所有命中
     * @param latencyMs 从发送前至校验完成的客户端毫秒耗时
     */
    record SearchResult(List<Hit> hits, double latencyMs) {
        SearchResult {
            hits = List.copyOf(hits);
        }
    }

    private final Transport transport;
    private final Inputs.Config config;
    private final LongSupplier clock;

    Protocol(Transport transport, Inputs.Config config, LongSupplier clock) {
        this.transport = transport;
        this.config = config;
        this.clock = clock;
    }

    void identity(String token, long expectedUserId) {
        exchange(request("/api/auth/me", "GET", token, new byte[0]), false, (root, deadline) -> {
            JsonNode data = root.path("data");
            JsonNode user = data.path("user");
            JsonNode id = user.path("userId");
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() != expectedUserId
                    || !user.path("enabled").isBoolean() || !user.path("enabled").booleanValue()
                    || !data.path("mustChangePassword").isBoolean() || data.path("mustChangePassword").booleanValue()) {
                throw new CollectionException(CollectionException.Code.IDENTITY, "identity", 0, "expectedUserId");
            }
            deadline.check();
            return Boolean.TRUE;
        });
    }

    SearchResult search(String token, Inputs.Case item) {
        return exchange(request("/api/search", "POST", token, item.body()), item.filtered(), (root, deadline) -> {
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new CollectionException(PROTOCOL, "response", item.line(), "data");
            }
            List<Hit> hits = new ArrayList<>();
            Set<Hit> unique = new HashSet<>();
            for (JsonNode node : data) {
                deadline.check();
                Hit hit = new Hit(id(node, "docId", item.line()), id(node, "versionId", item.line()),
                        id(node, "chunkId", item.line()));
                if (!unique.add(hit)) {
                    throw new CollectionException(PROTOCOL, "response", item.line(), "hits");
                }
                hits.add(hit);
            }
            return new SearchResult(hits, deadline.elapsedMs());
        });
    }

    private Transport.Request request(String path, String method, String token, byte[] body) {
        Map<String, String> headers = method.equals("POST")
                ? Map.of("satoken", token, "Accept", "application/json", "Accept-Encoding", "identity",
                        "Content-Type", "application/json; charset=UTF-8")
                : Map.of("satoken", token, "Accept", "application/json", "Accept-Encoding", "identity");
        return new Transport.Request(Endpoint.api(config.baseUrl(), path), method, headers, body);
    }

    private <T> T exchange(Transport.Request request, boolean filtered, BiFunction<JsonNode, Deadline, T> convert) {
        Deadline deadline = new Deadline(clock, config.requestTimeoutMs());
        deadline.check();
        AtomicReference<Transport.Response> active = new AtomicReference<>();
        AtomicBoolean abandoned = new AtomicBoolean();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<T> future = executor.submit(() -> {
            try (Transport.Response response = transport.send(request, deadline)) {
                active.set(response);
                if (abandoned.get()) {
                    throw new CollectionException(CollectionException.Code.INTERRUPTED, "response", 0, "request");
                }
                deadline.check();
                headers(response, filtered);
                byte[] bytes = JsonSupport.bounded(response.body(), config.maxResponseBytes(), deadline::check,
                        PROTOCOL, "response");
                JsonNode root = JsonSupport.parse(bytes, "response", 0, PROTOCOL, deadline::check);
                JsonNode code = root.path("code");
                if (!code.isIntegralNumber() || !code.canConvertToInt() || code.intValue() != 200) {
                    throw new CollectionException(PROTOCOL, "response", 0, "code");
                }
                T result = convert.apply(root, deadline);
                deadline.check();
                return result;
            } finally {
                active.set(null);
            }
        });
        try {
            T result = future.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            deadline.check();
            return result;
        } catch (TimeoutException e) {
            throw new CollectionException(CollectionException.Code.TIMEOUT, "response", 0, "request");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CollectionException(CollectionException.Code.INTERRUPTED, "response", 0, "request");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof CollectionException failure) {
                throw failure;
            }
            throw new CollectionException(CollectionException.Code.TRANSPORT, "response", 0, "request");
        } finally {
            abandoned.set(true);
            future.cancel(true);
            Transport.Response response = active.getAndSet(null);
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    // 已在失败路径；不传播响应内容或底层异常。
                }
            }
            executor.shutdownNow();
        }
    }

    static void headers(Transport.Response response, boolean filtered) {
        if (response.status() != 200) {
            throw new CollectionException(CollectionException.Code.HTTP, "response", 0, "status");
        }
        HttpHeaders headers = response.headers();
        List<String> encoding = headers.allValues("Content-Encoding");
        if (!encoding.isEmpty() && (encoding.size() != 1 || !encoding.getFirst().equalsIgnoreCase("identity"))) {
            throw new CollectionException(PROTOCOL, "response", 0, "encoding");
        }
        List<String> types = headers.allValues("Content-Type");
        if (types.size() != 1 || !types.getFirst().toLowerCase(Locale.ROOT)
                .matches("application/json\\s*(;\\s*charset\\s*=\\s*(utf-8|\"utf-8\")\\s*)?")) {
            throw new CollectionException(PROTOCOL, "response", 0, "contentType");
        }
        if (filtered && !headers.allValues("X-Search-Filter-Version").equals(List.of("1"))) {
            throw new CollectionException(PROTOCOL, "response", 0, "filterVersion");
        }
    }

    private static long id(JsonNode node, String field, int line) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new CollectionException(PROTOCOL, "response", line, field);
        }
        return value.longValue();
    }
}
