package com.zhishiku.notifier;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** JDK 网络回调只做有界复制；不使用会在返回后继续读取的 InputStream。 */
final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final Deadline deadline;
    private Flow.Subscription subscription;

    BoundedBody(Deadline deadline) {
        this.deadline = deadline;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
        if (subscription != null) {
            value.cancel();
            return;
        }
        subscription = value;
        subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> chunks) {
        try {
            deadline.remainingNanos();
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > BoundedJson.RESPONSE_BYTES - bytes.size()) {
                    throw new Rejection(503, Rejection.Reason.remote_json);
                }
                byte[] data = new byte[chunk.remaining()];
                chunk.get(data);
                bytes.writeBytes(data);
            }
            subscription.request(1);
        } catch (Rejection rejection) {
            subscription.cancel();
            result.completeExceptionally(rejection);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        // 不将原始网络异常体暴露给上层或日志。
        result.completeExceptionally(new Rejection(503, Rejection.Reason.network));
    }

    @Override
    public void onComplete() {
        try {
            deadline.remainingNanos();
            result.complete(bytes.toByteArray());
        } catch (Rejection rejection) {
            result.completeExceptionally(rejection);
        }
    }
}
