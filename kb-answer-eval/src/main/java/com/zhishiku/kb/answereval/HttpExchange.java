package com.zhishiku.kb.answereval;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 总期限覆盖响应头、正文读取和解析；失败只关闭当前流，不重发请求。 */
public final class HttpExchange {
    @FunctionalInterface
    public interface Reader<T> {
        T read(Transport.Response response, Deadline deadline) throws IOException;
    }

    private final Transport transport;

    public HttpExchange(Transport transport) {
        this.transport = transport;
    }

    public <T> T execute(Transport.Request request, Deadline deadline, Reader<T> reader) {
        deadline.check();
        AtomicReference<Transport.Response> active = new AtomicReference<>();
        AtomicBoolean abandoned = new AtomicBoolean();
        AtomicBoolean submitted = new AtomicBoolean();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var future = executor.submit(() -> {
            deadline.check();
            submitted.set(true);
            try (var response = transport.send(request, deadline)) {
                active.set(response);
                if (abandoned.get()) {
                    throw new EvaluationFailure(EvaluationFailure.Code.CANCELLED);
                }
                deadline.check();
                T result = reader.read(response, deadline);
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
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new EvaluationFailure(EvaluationFailure.Code.CANCELLED);
        } catch (TimeoutException failure) {
            throw new EvaluationFailure(submitted.get() && "POST".equals(request.method())
                    ? EvaluationFailure.Code.SUBMISSION_UNKNOWN : EvaluationFailure.Code.DEADLINE);
        } catch (EvaluationFailure failure) {
            // remainingNanos/check 也可能在调用方观察到期限耗尽，须与工作线程保持相同语义。
            if (failure.code() == EvaluationFailure.Code.DEADLINE && submitted.get() && "POST".equals(request.method())) {
                throw new EvaluationFailure(EvaluationFailure.Code.SUBMISSION_UNKNOWN);
            }
            throw failure;
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof EvaluationFailure known) {
                if (known.code() == EvaluationFailure.Code.DEADLINE && submitted.get() && "POST".equals(request.method())) {
                    throw new EvaluationFailure(EvaluationFailure.Code.SUBMISSION_UNKNOWN);
                }
                throw known;
            }
            if ((failure.getCause() instanceof IOException || failure.getCause() instanceof InterruptedException)
                    && submitted.get() && "POST".equals(request.method())) {
                throw new EvaluationFailure(EvaluationFailure.Code.SUBMISSION_UNKNOWN);
            }
            throw new EvaluationFailure(EvaluationFailure.Code.PROTOCOL);
        } finally {
            abandoned.set(true);
            future.cancel(true);
            var response = active.getAndSet(null);
            if (response != null) {
                try {
                    response.close();
                } catch (IOException ignored) {
                    // 只释放当前响应，不回显底层异常。
                }
            }
            executor.shutdownNow();
        }
    }

    public static String contentType(Transport.Response response, Set<Integer> statuses, Set<String> mediaTypes) {
        if (!statuses.contains(response.status())) {
            throw new EvaluationFailure(EvaluationFailure.Code.PROTOCOL);
        }
        List<String> encodings = response.headers().allValues("Content-Encoding");
        if (!encodings.isEmpty() && !encodings.equals(List.of("identity"))) {
            throw new EvaluationFailure(EvaluationFailure.Code.PROTOCOL);
        }
        List<String> types = response.headers().allValues("Content-Type");
        if (types.size() != 1) {
            throw new EvaluationFailure(EvaluationFailure.Code.PROTOCOL);
        }
        String[] parts = types.getFirst().toLowerCase(Locale.ROOT).split(";", -1);
        String type = parts[0].strip();
        if (!mediaTypes.contains(type) || parts.length > 2
                || parts.length == 2 && !parts[1].strip().matches("charset\\s*=\\s*(utf-8|\"utf-8\")")) {
            throw new EvaluationFailure(EvaluationFailure.Code.PROTOCOL);
        }
        return type;
    }
}
