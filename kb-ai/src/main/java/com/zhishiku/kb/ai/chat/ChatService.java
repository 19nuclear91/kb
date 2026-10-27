package com.zhishiku.kb.ai.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RAG 编排。
 *
 * <p>模型调用通过 {@link ChatModelClient} 注入。当前部署没有具体供应商 Bean 时，
 * 保留确定性的占位应答；接入模型不需要改动召回、引用和 SSE 收尾逻辑。
 * 模型适配器在流尾提供的累计 usage 会写入 {@link ChatEvent.Done}，由控制器交给
 * 部门 token 账本结算。引用与模型输入来自同一预算内列表，但不证明模型实际采用了材料。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final RetrievalClient retrievalClient;
    private final ObjectProvider<ChatModelClient> chatModelClients;
    private final AiProperties props;

    /**
     * 兼容当前无模型 Bean 的部署和直接构造测试。
     */
    public ChatService(RetrievalClient retrievalClient) {
        this(retrievalClient, null, null);
    }

    public ChatService(RetrievalClient retrievalClient, ObjectProvider<ChatModelClient> chatModelClients) {
        this(retrievalClient, chatModelClients, null);
    }

    @Autowired
    public ChatService(RetrievalClient retrievalClient, ObjectProvider<ChatModelClient> chatModelClients,
                       AiProperties props) {
        this.retrievalClient = retrievalClient;
        this.chatModelClients = chatModelClients;
        this.props = props;
    }

    /**
     * 执行一轮问答。
     *
     * <p><b>契约：返回的流必然以 {@link ChatEvent.Done} 或 {@link ChatEvent.Error} 结尾。</b>
     * {@code ChatController} 依赖这一点来结束 SSE 连接（它靠终止事件而非流完成来收尾），
     * 少了收尾事件前端会一直挂着等。下面用 {@code concatWith} 与
     * {@code onErrorResume} 把这个保证做实，而不是指望每条路径自己记得发。
     */
    public Flux<ChatEvent> chat(String question, String tokenValue) {
        return chat(question, tokenValue, ChatObservation.NONE);
    }

    public Flux<ChatEvent> chat(String question, String tokenValue, ChatObservation observation) {
        long startedAt = System.currentTimeMillis();

        if (question == null || question.isBlank()) {
            return Flux.just(new ChatEvent.Error("问题不能为空。"));
        }
        if (props != null && question.codePointCount(0, question.length()) > props.maxQuestionChars()) {
            return Flux.just(new ChatEvent.Error("问题过长，请缩短后重试。"));
        }

        return Flux.defer(() -> {
            UsageAccumulator usage = new UsageAccumulator();
            return retrievalClient.retrieve(question, tokenValue)
                    .flatMapMany(citations -> {
                        if (citations.isEmpty()) {
                            observation.result("NO_RESULTS");
                            // 空结果有两种成因：确实没有相关内容，或者用户无权看到任何内容。
                            // 【安全要求】对外必须给同一句话。若区分成
                            // "无权限查看"和"未找到"，用户就能靠提问反复探测
                            // 某份文档是否存在——这是一条信息泄露侧信道。
                            // 排障所需的区分信息只进服务端日志。
                            log.debug("召回为空 hasToken={}", tokenValue != null);
                            return Flux.<ChatEvent>just(
                                    new ChatEvent.Delta("未找到与问题相关的内容。"));
                        }

                        List<RetrievalClient.Citation> boundedCitations = boundContext(citations);
                        Flux<ChatEvent> citationEvents = Flux.fromIterable(boundedCitations)
                                .map(ChatEvent.Citation::from);

                        ChatModelClient modelClient = chatModelClients == null
                                ? null : chatModelClients.getIfAvailable();
                        Flux<ChatEvent> answer;
                        if (modelClient == null) {
                            observation.result("PLACEHOLDER");
                            answer = stubAnswer(boundedCitations);
                        } else {
                            answer = Mono.defer(observation::dispatch).thenMany(Flux.defer(() -> modelClient.streamEvents(question, boundedCitations)))
                                    .doOnNext(event -> { usage.accept(event); observation.usage(event); })
                                    .doOnComplete(observation::completedUsage)
                                    .filter(event -> event.text() != null)
                                    .map(event -> new ChatEvent.Delta(event.text()));
                        }
                        if (props != null) {
                            answer = answer.timeout(Duration.ofSeconds(Math.max(1, props.upstreamTimeout())));
                        }
                        return citationEvents.concatWith(answer);
                    })
                    .concatWith(Mono.fromSupplier(() ->
                            new ChatEvent.Done(usage.promptTokens(), usage.completionTokens(),
                                    System.currentTimeMillis() - startedAt)))
                    .onErrorResume(e -> {
                        SafeChatLog.error(log, "chat", e);
                        // 不回传 e.getMessage()：异常消息里可能带着上游返回的内容，
                        // 而那里面装的是文档正文。见 ChatEvent.Error 的说明。
                        return Flux.just(new ChatEvent.Error("处理请求时出错，请稍后重试。"));
                    });
        });
    }

    private static final class UsageAccumulator {
        private final AtomicInteger promptTokens = new AtomicInteger();
        private final AtomicInteger completionTokens = new AtomicInteger();

        void accept(ChatModelClient.StreamEvent event) {
            if (event.promptTokens() != null) promptTokens.set(event.promptTokens());
            if (event.completionTokens() != null) completionTokens.set(event.completionTokens());
        }

        int promptTokens() {
            return promptTokens.get();
        }

        int completionTokens() {
            return completionTokens.get();
        }
    }

    /**
     * 按 code point 总预算裁剪正文；身份和原分块来源范围保持不变。
     */
    private List<RetrievalClient.Citation> boundContext(List<RetrievalClient.Citation> citations) {
        if (props == null) return citations;
        int remaining = props.maxContextChars();
        var result = new ArrayList<RetrievalClient.Citation>(citations.size());
        for (RetrievalClient.Citation citation : citations) {
            if (remaining <= 0) break;
            String content = citation.content() == null ? "" : citation.content();
            String bounded = truncateCodePoints(content, remaining);
            result.add(citation.withContextContent(bounded, !bounded.equals(content)));
            remaining -= bounded.codePointCount(0, bounded.length());
        }
        return result;
    }

    private static String truncateCodePoints(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) return value;
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end);
    }

    /**
     * 占位应答：把召回结果的标题逐字吐出。
     *
     * <p>每字之间刻意加延迟，这样 {@code curl -N} 能肉眼看出是不是真的在流式输出。
     * 若某一跳存在缓冲，症状会非常明显——所有字一起到达。
     *
     * <p>真实模型由 {@link ChatModelClient} 提供；该占位分支只在没有注入模型适配器时使用。
     * 占位分支不产生 token usage，因此不会虚构账本消耗。
     */
    private Flux<ChatEvent> stubAnswer(List<RetrievalClient.Citation> citations) {
        String text = "【占位应答，尚未接入模型】共召回 " + citations.size()
                + " 个片段，来自：" + citations.stream()
                .map(RetrievalClient.Citation::docTitle)
                .filter(title -> title != null && !title.isBlank())
                .distinct()
                .reduce((a, b) -> a + "、" + b)
                .orElse("（无）");

        return Flux.fromArray(text.split(""))
                .delayElements(Duration.ofMillis(40))
                .map(ChatEvent.Delta::new);
    }
}
