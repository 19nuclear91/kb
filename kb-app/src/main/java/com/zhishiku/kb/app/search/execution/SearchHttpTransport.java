package com.zhishiku.kb.app.search.execution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.common.embedding.CallBudget;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 整段调用接受阶段预算，分段读取、出站编码与严格解析均在字节上限内。 */
@Component
public class SearchHttpTransport implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Exchange exchange;
    private final ExecutorService io;
    private final HttpClient client;

    @Autowired
    public SearchHttpTransport() {
        io = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), task -> {
            Thread thread = new Thread(task, "search-http");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        client = HttpClient.newBuilder().executor(io).connectTimeout(Duration.ofSeconds(30)).build();
        exchange = request -> {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new Response(response.statusCode(), response.body());
        };
    }

    public SearchHttpTransport(Exchange exchange) {
        this.exchange = exchange;
        io = null;
        client = null;
    }

    public Map<String, Object> post(URI uri, String apiKey, Map<String, Object> body, CallBudget budget,
                                     int maxRequestBytes, int timeoutSeconds) {
        budget.check();
        byte[] request = encode(body, maxRequestBytes, budget);
        budget.check();
        long timeout = Math.min(TimeUnit.SECONDS.toNanos(timeoutSeconds), budget.remainingNanos());
        if (timeout <= 0) {
            throw new SearchStageFailure(SearchStageFailure.Reason.TIME_BUDGET);
        }
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(timeout))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(request));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        try (var deadline = budget.limitTo(timeout)) {
            var response = exchange.send(builder.build());
            try (InputStream input = response.body(); var registration = budget.onCancel(() -> close(input))) {
                budget.check();
                if (response.status() != 200) {
                    throw new SearchStageFailure(SearchStageFailure.Reason.UPSTREAM);
                }
                byte[] bytes = read(input, budget);
                Map<String, Object> result = json.readValue(bytes, new TypeReference<>() { });
                budget.check();
                if (result == null) {
                    throw new SearchStageFailure(SearchStageFailure.Reason.INVALID_RESPONSE);
                }
                return result;
            }
        } catch (com.fasterxml.jackson.core.JacksonException malformed) {
            throw new SearchStageFailure(SearchStageFailure.Reason.INVALID_RESPONSE);
        } catch (HttpTimeoutException timeoutFailure) {
            throw new SearchStageFailure(SearchStageFailure.Reason.TIMEOUT);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new SearchFailure(SearchFailure.Kind.CANCELLED);
        } catch (IOException unavailable) {
            budget.check();
            throw new SearchStageFailure(SearchStageFailure.Reason.UPSTREAM);
        } catch (RejectedExecutionException busy) {
            throw new SearchStageFailure(SearchStageFailure.Reason.CAPACITY);
        }
    }

    private byte[] encode(Map<String, Object> body, int maximum, CallBudget budget) {
        var output = new ByteArrayOutputStream() {
            @Override
            public synchronized void write(byte[] bytes, int offset, int length) {
                budget.check();
                if (length > maximum - count) {
                    throw new SearchStageFailure(SearchStageFailure.Reason.PAYLOAD_LIMIT);
                }
                super.write(bytes, offset, length);
            }
        };
        try {
            json.writeValue(output, body);
            return output.toByteArray();
        } catch (IOException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SearchStageFailure known) {
                    throw known;
                }
                if (cause instanceof SearchFailure known) {
                    throw known;
                }
            }
            throw new IllegalStateException("搜索出站编码失败");
        }
    }

    private static byte[] read(InputStream input, CallBudget budget) throws IOException {
        int maximum = budget.maxResponseBytes();
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[Math.min(8192, maximum + 1)];
        while (true) {
            budget.check();
            int count = input.read(buffer, 0, Math.min(buffer.length, maximum - output.size() + 1));
            budget.check();
            if (count < 0) {
                return output.toByteArray();
            }
            if (count > maximum - output.size()) {
                throw new SearchStageFailure(SearchStageFailure.Reason.PAYLOAD_LIMIT);
            }
            output.write(buffer, 0, count);
        }
    }

    private static void close(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // 原调用实际退出前继续计入容量。
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (client != null) {
            client.shutdownNow();
            io.shutdownNow();
        }
    }

    @FunctionalInterface
    public interface Exchange {
        Response send(HttpRequest request) throws IOException, InterruptedException;
    }

    public record Response(int status, InputStream body) {
    }
}
