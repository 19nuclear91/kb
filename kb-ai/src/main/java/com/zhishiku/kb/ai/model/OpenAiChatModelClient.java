package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.chat.ChatModelClient;
import com.zhishiku.kb.ai.chat.RetrievalClient;
import reactor.core.publisher.Flux;

import java.util.List;

/** 旧单轮桥接；实际用量不截断，未知用量不补零。 */
public class OpenAiChatModelClient implements ChatModelClient {
    private final ChatPromptRenderer renderer;
    private final OpenAiChatTransport transport;
    private final int outputTokens;

    public OpenAiChatModelClient(ChatPromptRenderer renderer, OpenAiChatTransport transport, int outputTokens) {
        this.renderer = renderer;
        this.transport = transport;
        this.outputTokens = outputTokens;
    }

    @Override
    public Flux<String> stream(String question, List<RetrievalClient.Citation> citations) {
        return streamEvents(question, citations).filter(event -> event.text() != null).map(StreamEvent::text);
    }

    @Override
    public Flux<StreamEvent> streamEvents(String question, List<RetrievalClient.Citation> citations) {
        return ModelStreams.once(() -> transport.stream(renderer.single(question, citations, outputTokens))
                .map(event -> {
                    if (event instanceof ModelEvent.Delta delta) {
                        return StreamEvent.delta(delta.text());
                    }
                    if (event instanceof ModelEvent.FinalUsage usage) {
                        return StreamEvent.usage(Math.toIntExact(usage.promptTokens()),
                                Math.toIntExact(usage.completionTokens()));
                    }
                    throw new ChatModelFailure(ChatModelFailure.Kind.PROTOCOL_INVALID);
                }));
    }
}
