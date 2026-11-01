package com.zhishiku.kb.ai.model;

import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 桥接层延迟准备输入；重复订阅不重新创建一个出站执行。 */
final class ModelStreams {
    private ModelStreams() {
    }

    static <T> Flux<T> once(Supplier<Flux<T>> source) {
        var consumed = new AtomicBoolean();
        return Flux.defer(() -> {
            if (!consumed.compareAndSet(false, true)) {
                return Flux.error(new ChatModelFailure(ChatModelFailure.Kind.ALREADY_SUBSCRIBED));
            }
            return source.get();
        }).onErrorMap(error -> error instanceof ChatModelFailure ? error
                : new ChatModelFailure(ChatModelFailure.Kind.PROTOCOL_INVALID));
    }
}
