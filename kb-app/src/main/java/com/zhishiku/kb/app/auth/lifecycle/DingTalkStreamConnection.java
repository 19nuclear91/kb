package com.zhishiku.kb.app.auth.lifecycle;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** 单连接、有界回调队列及关闭；失败让平台重投，不在网络层伪造可靠接收。 */
@Component
public class DingTalkStreamConnection implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(DingTalkStreamConnection.class);
    private final DingTalkLifecycleSettings settings;
    private final LifecycleReadiness readiness;
    private final DingTalkStreamProtocol protocol;
    private final Dialer dialer;
    private final Supplier<ScheduledExecutorService> timers;
    private volatile boolean running;
    private volatile Channel channel;
    private volatile java.time.Instant lastConnectedAt;
    private ThreadPoolExecutor workers;
    private ScheduledExecutorService reconnect;

    interface Channel {
        boolean active();
        void abort();
    }

    interface Dialer extends AutoCloseable {
        Channel open(Function<String, CompletableFuture<DingTalkStreamProtocol.Reply>> receive);
        @Override
        void close();
    }

    @Autowired
    public DingTalkStreamConnection(DingTalkLifecycleSettings settings, LifecycleReadiness readiness,
                                    DingTalkStreamProtocol protocol, LifecycleHttp http) {
        this(settings, readiness, protocol, new JdkDialer(settings, http, protocol::rejected));
    }

    DingTalkStreamConnection(DingTalkLifecycleSettings settings, LifecycleReadiness readiness,
                             DingTalkStreamProtocol protocol, Dialer dialer) {
        this(settings, readiness, protocol, dialer, () -> Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lifecycle-stream");
            thread.setDaemon(true);
            return thread;
        }));
    }

    DingTalkStreamConnection(DingTalkLifecycleSettings settings, LifecycleReadiness readiness,
                             DingTalkStreamProtocol protocol, Dialer dialer, Supplier<ScheduledExecutorService> timers) {
        this.settings = settings;
        this.readiness = readiness;
        this.protocol = protocol;
        this.dialer = dialer;
        this.timers = timers;
    }

    @Override
    public synchronized void start() {
        if (running || !settings.enabled()) {
            return;
        }
        readiness.requireReady();
        workers = LifecycleHttp.executor("lifecycle-events", settings.workers(), settings.queueCapacity());
        reconnect = timers.get();
        running = true;
        reconnect.scheduleWithFixedDelay(this::connectIfNeeded, 0, 5, TimeUnit.SECONDS);
    }

    void connectIfNeeded() {
        if (!running || (channel != null && channel.active())) {
            return;
        }
        try {
            readiness.requireReady();
            Channel candidate = dialer.open(this::receive);
            synchronized (this) {
                if (!running) {
                    candidate.abort();
                    return;
                }
                Channel previous = channel;
                channel = candidate;
                lastConnectedAt = java.time.Instant.now();
                if (previous != null) {
                    previous.abort();
                }
            }
            LOG.info("生命周期 Stream 连接可用");
        } catch (RuntimeException failure) {
            LOG.warn("生命周期 Stream 连接暂不可用");
        }
    }

    private CompletableFuture<DingTalkStreamProtocol.Reply> receive(String frame) {
        var result = new CompletableFuture<DingTalkStreamProtocol.Reply>();
        if (!running) {
            result.complete(new DingTalkStreamProtocol.Reply(null, true));
            return result;
        }
        try {
            workers.execute(() -> {
                try {
                    result.complete(protocol.handle(frame));
                } catch (RuntimeException failure) {
                    result.complete(new DingTalkStreamProtocol.Reply(null, true));
                }
            });
        } catch (RuntimeException fullOrStopped) {
            if (running) {
                protocol.rejected(DingTalkInputRejected.Reason.QUEUE_FULL);
            }
            result.complete(new DingTalkStreamProtocol.Reply(null, true));
        }
        return result;
    }

    @Override
    @PreDestroy
    public void stop() {
        ThreadPoolExecutor pending;
        synchronized (this) {
            running = false;
            if (channel != null) {
                channel.abort();
                channel = null;
            }
            if (reconnect != null) {
                reconnect.shutdownNow();
            }
            pending = workers;
            if (pending != null) {
                pending.shutdown();
            }
        }
        dialer.close();
        if (pending != null) {
            try {
                if (!pending.awaitTermination(5, TimeUnit.SECONDS)) {
                    pending.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                pending.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public boolean connected() {
        Channel current = channel;
        return running && current != null && current.active();
    }

    public java.time.Instant lastConnectedAt() {
        return lastConnectedAt;
    }

    @Override
    public boolean isAutoStartup() {
        return settings.enabled();
    }

    private static final class JdkDialer implements Dialer {
        private final DingTalkLifecycleSettings settings;
        private final LifecycleHttp http;
        private final ThreadPoolExecutor io = LifecycleHttp.executor("lifecycle-websocket", 2, 32);
        private final HttpClient client;
        private volatile boolean closed;
        private final Consumer<DingTalkInputRejected.Reason> rejected;

        private JdkDialer(DingTalkLifecycleSettings settings, LifecycleHttp http, Consumer<DingTalkInputRejected.Reason> rejected) {
            this.settings = settings;
            this.http = http;
            this.rejected = rejected;
            client = HttpClient.newBuilder().executor(io).connectTimeout(Duration.ofMillis(settings.timeoutMillis()))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }

        @Override
        public Channel open(Function<String, CompletableFuture<DingTalkStreamProtocol.Reply>> receive) {
            if (closed) {
                throw LifecycleFailures.unavailable();
            }
            var response = http.post("https://api.dingtalk.com/v1.0/gateway/connections/open",
                    Map.of("clientId", settings.clientId(), "clientSecret", settings.clientSecret(),
                            "ua", "kb-lifecycle/1.0", "subscriptions", List.of(Map.of("type", "EVENT", "topic", "*"))));
            if (!response.path("endpoint").isTextual() || !response.path("ticket").isTextual()
                    || response.path("ticket").textValue().isBlank() || response.path("ticket").textValue().length() > 4096) {
                throw LifecycleFailures.unavailable();
            }
            URI endpoint = settings.requireStreamEndpoint(response.path("endpoint").textValue());
            URI target = URI.create("wss://" + endpoint.getHost() + "/connect?ticket="
                    + URLEncoder.encode(response.path("ticket").textValue(), StandardCharsets.UTF_8));
            var pending = client.newWebSocketBuilder().connectTimeout(Duration.ofMillis(settings.timeoutMillis()))
                    .buildAsync(target, new Frames(settings.maxMessageBytes(), settings.timeoutMillis(), receive, rejected));
            try {
                WebSocket socket = pending.get(settings.timeoutMillis(), TimeUnit.MILLISECONDS);
                if (closed) {
                    socket.abort();
                    throw LifecycleFailures.unavailable();
                }
                return new Channel() {
                    @Override
                    public boolean active() {
                        return !socket.isInputClosed() && !socket.isOutputClosed();
                    }

                    @Override
                    public void abort() {
                        socket.abort();
                    }
                };
            } catch (Exception failure) {
                pending.thenAccept(WebSocket::abort);
                pending.cancel(true);
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw LifecycleFailures.unavailable();
            }
        }

        @Override
        public void close() {
            closed = true;
            client.shutdownNow();
            io.shutdownNow();
        }
    }

    static final class Frames implements WebSocket.Listener {
        private final int maximum;
        private final int timeout;
        private final Function<String, CompletableFuture<DingTalkStreamProtocol.Reply>> receiver;
        private final StringBuilder pending = new StringBuilder();
        private final Consumer<DingTalkInputRejected.Reason> rejected;

        Frames(int maximum, int timeout, Function<String, CompletableFuture<DingTalkStreamProtocol.Reply>> receiver) {
            this(maximum, timeout, receiver, ignored -> { });
        }

        Frames(int maximum, int timeout, Function<String, CompletableFuture<DingTalkStreamProtocol.Reply>> receiver,
               Consumer<DingTalkInputRejected.Reason> rejected) {
            this.maximum = maximum;
            this.timeout = timeout;
            this.receiver = receiver;
            this.rejected = rejected;
        }

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if (data.length() > maximum - pending.length()) {
                rejected.accept(DingTalkInputRejected.Reason.MESSAGE_LIMIT);
                pending.setLength(0);
                socket.abort();
                return CompletableFuture.completedFuture(null);
            }
            pending.append(data);
            if (!last) {
                socket.request(1);
                return CompletableFuture.completedFuture(null);
            }
            String frame = pending.toString();
            pending.setLength(0);
            if (frame.getBytes(StandardCharsets.UTF_8).length > maximum) {
                rejected.accept(DingTalkInputRejected.Reason.MESSAGE_LIMIT);
                socket.abort();
                return CompletableFuture.completedFuture(null);
            }
            // 等当前 ACK 发完再请求下一帧，避免慢发送积累无界队列或并行 sendText。
            return receiver.apply(frame).thenCompose(reply -> {
                if (reply.disconnect()) {
                    socket.abort();
                    return CompletableFuture.completedFuture(null);
                }
                if (reply.text() == null) {
                    socket.request(1);
                    return CompletableFuture.completedFuture(null);
                }
                return socket.sendText(reply.text(), true).orTimeout(timeout, TimeUnit.MILLISECONDS)
                        .thenAccept(sent -> socket.request(1));
            }).exceptionally(failure -> {
                socket.abort();
                return null;
            });
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            rejected.accept(DingTalkInputRejected.Reason.UNSUPPORTED_COMMAND);
            socket.abort();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            socket.abort();
        }
    }
}
