package com.zhishiku.kb.worker.embedding.execution;

import com.zhishiku.kb.common.embedding.CallBudget;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProtocolFailure;
import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.common.embedding.RemoteEmbeddingTransport;
import org.springframework.http.MediaType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 每个子批拥有独立客户端，确认本地终止后才返回，避免取消句柄冒充实际退出。 */
public final class WorkerEmbeddingTransport {
    private final WorkerEmbeddingSettings settings;
    private final WorkerEmbeddingJson json;
    private final Supplier<Session> sessions;
    private final WorkerEmbeddingMetrics metrics;

    public WorkerEmbeddingTransport(WorkerEmbeddingSettings settings, WorkerEmbeddingJson json) {
        this(settings, json, JdkSession::new, null);
    }

    public WorkerEmbeddingTransport(WorkerEmbeddingSettings settings, WorkerEmbeddingJson json, WorkerEmbeddingMetrics metrics) {
        this(settings, json, JdkSession::new, metrics);
    }

    public WorkerEmbeddingTransport(WorkerEmbeddingSettings settings, WorkerEmbeddingJson json, Supplier<Session> sessions) {
        this(settings, json, sessions, null);
    }

    public WorkerEmbeddingTransport(WorkerEmbeddingSettings settings, WorkerEmbeddingJson json,
                                     Supplier<Session> sessions, WorkerEmbeddingMetrics metrics) {
        this.settings = settings;
        this.json = json;
        this.sessions = sessions;
        this.metrics = metrics;
    }

    public ProfileEmbeddingClient observe(ProfileEmbeddingClient client) {
        return new ProfileEmbeddingClient() {
            @Override
            public EmbeddingProfile profile() {
                return client.profile();
            }

            @Override
            public EmbeddingRole role() {
                return client.role();
            }

            @Override
            public EmbeddingResult embed(List<String> texts) {
                return client.embed(texts);
            }

            @Override
            public EmbeddingResult embed(List<String> texts, CallBudget budget) {
                if (!(budget instanceof WorkerExecution execution)) {
                    throw new IllegalStateException("Worker execution required");
                }
                Supplier<EmbeddingResult> action = () -> {
                    try {
                        return client.embed(texts, execution);
                    } catch (EmbeddingProtocolFailure invalid) {
                        throw new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.INVALID_RESPONSE);
                    }
                };
                return metrics == null ? action.get() : metrics.observe(execution, action);
            }
        };
    }

    public RemoteEmbeddingTransport forProfile(EmbeddingProfileDefinition definition) {
        return new RemoteEmbeddingTransport() {
            @Override
            public Map<String, Object> post(Map<String, Object> request) {
                throw new IllegalStateException("Worker embedding budget required");
            }

            @Override
            public Map<String, Object> post(Map<String, Object> request, CallBudget budget) {
                if (!(budget instanceof WorkerExecution execution)) {
                    throw new IllegalStateException("Worker execution required");
                }
                return exchange(definition, request, execution);
            }
        };
    }

    private Map<String, Object> exchange(EmbeddingProfileDefinition definition, Map<String, Object> body,
                                         WorkerExecution execution) {
        execution.check();
        long nanos = Math.min(TimeUnit.SECONDS.toNanos(definition.connection().timeoutSeconds()), execution.remainingNanos());
        try (var deadline = execution.limitTo(nanos)) {
            byte[] encoded = json.encode(body, execution);
            execution.check();
            Session session = sessions.get();
            var retained = execution.retainResource();
            var responseBody = new AtomicReference<InputStream>();
            var pending = new AtomicReference<CompletableFuture<HttpResponse<InputStream>>>();
            var cancellation = execution.onCancel(() -> {
                var request = pending.get();
                if (request != null) {
                    request.cancel(true);
                }
                session.shutdown();
                closeBody(responseBody);
            });
            boolean interrupted = false;
            Map<String, Object> result;
            try {
                String endpoint = definition.connection().endpoint().replaceAll("/+$", "") + "/embeddings";
                var request = HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(Duration.ofNanos(Math.max(1, execution.remainingNanos())))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(encoded));
                String key = definition.connection().apiKey();
                if (key != null && !key.isBlank()) {
                    request.header("Authorization", "Bearer " + key);
                }
                execution.check();
                int limit = Math.min(settings.maxResponseBytes(), execution.maxResponseBytes());
                var future = session.send(request.build(), info -> subscriber(info, responseBody, limit));
                pending.set(future);
                if (execution.stopped()) {
                    future.cancel(true);
                }
                execution.check();
                HttpResponse<InputStream> response = future.get(execution.remainingNanos(), TimeUnit.NANOSECONDS);
                execution.check();
                byte[] bytes = read(response.body(), limit, execution);
                result = json.decode(bytes, execution);
                execution.check();
            } catch (InterruptedException error) {
                interrupted = true;
                execution.stop(WorkerEmbeddingFailure.Reason.SHUTDOWN);
                throw new WorkerEmbeddingFailure(execution.reason());
            } catch (TimeoutException error) {
                execution.stop(WorkerEmbeddingFailure.Reason.TIMEOUT);
                throw new WorkerEmbeddingFailure(execution.reason());
            } catch (ExecutionException error) {
                throw stop(execution, classify(error.getCause()));
            } catch (CancellationException error) {
                throw stop(execution, new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.NETWORK));
            } catch (IOException error) {
                throw stop(execution, classify(error));
            } catch (WorkerEmbeddingFailure failure) {
                throw stop(execution, failure);
            } catch (IllegalArgumentException invalidRequest) {
                throw stop(execution, new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.HTTP_REJECTED));
            } catch (RejectedExecutionException unavailable) {
                throw stop(execution, new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.NETWORK));
            } finally {
                try {
                    var future = pending.get();
                    if (future != null && !future.isDone()) {
                        future.cancel(true);
                    }
                    session.shutdown();
                    boolean terminated = false;
                    while (!terminated) {
                        closeBody(responseBody);
                        try {
                            terminated = session.awaitTermination(Duration.ofMillis(100));
                        } catch (InterruptedException error) {
                            interrupted = true;
                        }
                    }
                    closeBody(responseBody);
                    retained.close();
                } catch (RuntimeException cleanupFailure) {
                    throw stop(execution, new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.NETWORK));
                } finally {
                    cancellation.close();
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            execution.check();
            return result;
        }
    }

    private static WorkerEmbeddingFailure stop(WorkerExecution execution, WorkerEmbeddingFailure failure) {
        execution.stop(failure.reason());
        return new WorkerEmbeddingFailure(execution.reason());
    }

    private static WorkerEmbeddingFailure classify(Throwable error) {
        if (error instanceof WorkerEmbeddingFailure failure) {
            return failure;
        }
        if (error instanceof HttpTimeoutException) {
            return new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.TIMEOUT);
        }
        if (error instanceof IOException) {
            return new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.NETWORK);
        }
        // 未识别的执行错误仍走原未知故障路径，不将程序错误伪装为网络故障。
        throw new IllegalStateException("Worker embedding transport unavailable");
    }

    private static HttpResponse.BodySubscriber<InputStream> subscriber(HttpResponse.ResponseInfo info,
                                                                       AtomicReference<InputStream> body, int limit) {
        if (info.statusCode() != 200) {
            return new RejectedBody(WorkerEmbeddingFailure.http(info.statusCode()));
        }
        try {
            if (info.headers().firstValueAsLong("Content-Length").orElse(0) > limit) {
                return new RejectedBody(new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.RESPONSE_LIMIT));
            }
            String encoding = info.headers().firstValue("Content-Encoding").orElse("identity");
            var type = MediaType.parseMediaType(info.headers().firstValue("Content-Type").orElse("application/json"));
            if (!"identity".equalsIgnoreCase(encoding) || !"application".equalsIgnoreCase(type.getType())
                    || !("json".equalsIgnoreCase(type.getSubtype()) || type.getSubtype().endsWith("+json"))
                    || (type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset()))) {
                return new RejectedBody(new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.INVALID_RESPONSE));
            }
        } catch (RuntimeException invalid) {
            return new RejectedBody(new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.INVALID_RESPONSE));
        }
        var delegate = HttpResponse.BodySubscribers.ofInputStream();
        return new HttpResponse.BodySubscriber<>() {
            @Override
            public CompletionStage<InputStream> getBody() {
                return delegate.getBody().thenApply(input -> {
                    body.set(input);
                    return input;
                });
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                delegate.onSubscribe(subscription);
            }

            @Override
            public void onNext(List<ByteBuffer> buffers) {
                delegate.onNext(buffers);
            }

            @Override
            public void onError(Throwable error) {
                delegate.onError(error);
            }

            @Override
            public void onComplete() {
                delegate.onComplete();
            }
        };
    }

    private static byte[] read(InputStream input, int maximum, WorkerExecution execution) throws IOException {
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[Math.min(8192, maximum + 1)];
        while (true) {
            execution.check();
            int count = input.read(buffer, 0, Math.min(buffer.length, maximum - output.size() + 1));
            execution.check();
            if (count < 0) {
                return output.toByteArray();
            }
            if (count > maximum - output.size()) {
                throw new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.RESPONSE_LIMIT);
            }
            output.write(buffer, 0, count);
        }
    }

    private static void closeBody(AtomicReference<InputStream> body) {
        InputStream input = body.getAndSet(null);
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
                // 客户端还须终止，不能仅据 close 返回归还执行容量。
            }
        }
    }

    public interface Session {
        CompletableFuture<HttpResponse<InputStream>> send(HttpRequest request, HttpResponse.BodyHandler<InputStream> handler);
        void shutdown();
        boolean awaitTermination(Duration timeout) throws InterruptedException;
    }

    private static final class RejectedBody implements HttpResponse.BodySubscriber<InputStream> {
        private final CompletableFuture<InputStream> body = new CompletableFuture<>();
        private final WorkerEmbeddingFailure failure;

        private RejectedBody(WorkerEmbeddingFailure failure) {
            this.failure = failure;
        }

        @Override
        public CompletionStage<InputStream> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
            body.completeExceptionally(failure);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.completeExceptionally(failure);
        }
    }

    private static final class JdkSession implements Session {
        private final ThreadPoolExecutor io = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), action -> {
                    var thread = new Thread(action, "worker-embedding-http");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        private final HttpClient client = HttpClient.newBuilder().executor(io)
                .connectTimeout(Duration.ofSeconds(60)).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress address, IOException error) {
                        // 完整远程地址不进入诊断。
                    }
                }).build();

        @Override
        public CompletableFuture<HttpResponse<InputStream>> send(HttpRequest request,
                                                                  HttpResponse.BodyHandler<InputStream> handler) {
            return client.sendAsync(request, handler);
        }

        @Override
        public void shutdown() {
            client.shutdownNow();
        }

        @Override
        public boolean awaitTermination(Duration timeout) throws InterruptedException {
            if (!client.awaitTermination(timeout)) {
                return false;
            }
            io.shutdown();
            return io.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }
}
