package com.zhishiku.kb.ai.model;

/** 协议内部事件；Activity 和 End 不对业务或浏览器输出。 */
public sealed interface ModelEvent {
    record Delta(String text) implements ModelEvent {
        @Override
        public String toString() {
            return "ModelDelta";
        }
    }

    record FinalUsage(long promptTokens, long completionTokens) implements ModelEvent {
    }

    record Failure(ChatModelFailure error) implements ModelEvent {
    }

    enum Signal implements ModelEvent {
        ACTIVITY, END
    }
}
