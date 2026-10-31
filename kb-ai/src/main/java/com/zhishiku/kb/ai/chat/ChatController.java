package com.zhishiku.kb.ai.chat;

import com.zhishiku.kb.common.security.AuthHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import com.zhishiku.kb.ai.observation.AiObservedChat;

import java.time.Duration;

/**
 * 流式对话接口。
 *
 * <p>P0 阶段这个接口的目的不是"能回答问题"，而是<b>把流式链路打通并可验证</b>：
 * 浏览器 → 网关 → kb-ai → kb-app 授权检索，每一跳都不缓冲、不超时掐断、
 * Sa-Token 会话一路透传。这条链路上任何一跳配错，表现都是同一个症状
 * （憋到最后一次性返回），所以必须端到端实测，看配置文件不算。
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI 对话", description = "基于授权检索结果的 SSE 流式问答")
@Validated
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final AiProperties props;
    private final ChatQuotaAuditService quotaAudit;
    private AiObservedChat observed;

    @Autowired
    public void setObservedChat(AiObservedChat observed) { this.observed = observed; }

    public ChatController(ChatService chatService, AiProperties props) {
        this(chatService, props, null);
    }

    @Autowired
    public ChatController(ChatService chatService, AiProperties props, ChatQuotaAuditService quotaAudit) {
        this.chatService = chatService;
        this.props = props;
        this.quotaAudit = quotaAudit;
    }

    /**
     * 流式问答。
     *
     * <p>使用 POST + SSE 响应：问题放在 JSON 请求体，避免敏感查询词进入 URL、
     * 网关访问日志和代理缓存键。前端使用 fetch 读取 response.body 即可消费 SSE。
     *
     * @param tokenValue Sa-Token 由客户端携带并透传。{@code required = false} 是为了让缺失令牌时
     *                   走到下游被正常拒绝，而不是在这里返回一个含义模糊的 400；
     *                   <b>绝不在此处补默认用户</b>。
     * @param request    请求体，包含用户问题；问题不会写入 URL
     * @return SSE 事件流，事件类型包括 {@code delta}、{@code citation}、{@code done} 和 {@code error}
     */
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式问答", description = "通过 SSE 持续返回 delta、citation、done 或 error 事件")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE 流建立成功",
                    content = @Content(schema = @Schema(implementation = ChatEvent.class))),
            @ApiResponse(responseCode = "500", description = "登录状态无效或服务处理失败")
    })
    public Flux<ServerSentEvent<Object>> chat(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true, description = "问题请求体")
            @Valid @RequestBody ChatRequest request,
            @Parameter(description = "Sa-Token 会话令牌", hidden = true)
            @RequestHeader(value = AuthHeaders.SA_TOKEN, required = false) String tokenValue) {
        return stream(request.question(), tokenValue);
    }

    /**
     * 兼容已有直接调用方；HTTP 接口只暴露 POST。
     *
     * @param question   用户问题
     * @param tokenValue Sa-Token 会话令牌；由下游服务校验
     * @return 与 HTTP 接口相同的 SSE 事件流
     */
    public Flux<ServerSentEvent<Object>> chat(String question, String tokenValue) {
        return stream(question, tokenValue);
    }

    private Flux<ServerSentEvent<Object>> stream(String question, String tokenValue) {
        if (observed != null && observed.enabled()) return observed.stream(question, tokenValue);
        // 不记 question 内容，只记长度。理由同 RetrievalClient 里的说明。
        log.debug("收到流式问答请求 questionLength={} hasToken={}",
                question == null ? 0 : question.length(), tokenValue != null);

        if (quotaAudit == null) {
            return streamWithLease(question, tokenValue, null);
        }

        // 配额主体解析和 Redis 原子领取都必须在订阅时执行，避免控制器创建流时
        // 提前占用配额或让同一个冷流被多次复用。
        return Mono.defer(() -> quotaAudit.acquire(tokenValue,
                        question == null ? 0 : question.length()))
                .flatMapMany(lease -> streamWithLease(question, tokenValue, lease))
                .onErrorResume(ChatQuotaAuditService.QuotaExceededException.class,
                        e -> errorEvent(e.getMessage()))
                // 主体被拒（kb-app 返回 401/403：会话无效，或用户/部门已停用）必须
                // 先于通用的"配额不可用"处理：前者引导重新登录或联系管理员，后者
                // 才是"稍后重试"；折叠进服务不可用会让用户对着一个重试解决不了的
                // 问题反复重试。文案来自异常本身，不再替换成通用文案。
                .onErrorResume(QuotaPrincipalRejectedException.class,
                        e -> errorEvent(e.getMessage()))
                .onErrorResume(QuotaUnavailableException.class,
                        e -> errorEvent("配额服务暂时不可用，请稍后重试。"));
    }

    private Flux<ServerSentEvent<Object>> streamWithLease(
            String question, String tokenValue, ChatQuotaAuditService.Lease lease) {

        ChatQuotaAuditService.Lease activeLease = lease;
        Flux<ChatEvent> managedEvents = Flux.defer(() -> chatService.chat(question, tokenValue))
                // 先完成租约释放/审计，再把 done/error 发给客户端，避免连接结束时
                // fire-and-forget 的 Redis 操作还没开始就被取消。
                .concatMap(event -> {
                    if (activeLease == null) return Mono.just(event);
                    if (event instanceof ChatEvent.Done done) {
                        return activeLease.completeAsync(done.promptTokens(), done.completionTokens())
                                .thenReturn(event);
                    }
                    if (event instanceof ChatEvent.Error) {
                        return activeLease.failAsync().onErrorResume(error -> {
                            SafeChatLog.error(log, "stream_settlement", error);
                            return Mono.empty();
                        }).thenReturn(event);
                    }
                    return Mono.just(event);
                })
                .onErrorResume(error -> {
                    SafeChatLog.error(log, "stream", error);
                    Mono<Void> settle = activeLease == null
                            ? Mono.empty()
                            : activeLease.failAsync().onErrorResume(settlementError -> {
                        SafeChatLog.error(log, "stream_settlement", settlementError);
                        return Mono.empty();
                    });
                    return settle.then(Mono.just((ChatEvent)
                            new ChatEvent.Error("处理请求时出错，请稍后重试。")));
                })
                .doFinally(signal -> {
                    // 正常 done/error 已经在 concatMap 中结算；没有终止事件就释放为 cancelled。
                    if (activeLease != null
                            && (signal == SignalType.CANCEL || signal == SignalType.ON_COMPLETE)) {
                        activeLease.cancelAsync().subscribe(null,
                                error -> SafeChatLog.error(log, "cancel_settlement", error));
                    }
                });
        Flux<ServerSentEvent<Object>> body = managedEvents
                .map(e -> ServerSentEvent.builder()
                        .event(e.type())
                        .data((Object) e)
                        .build());

        // ========================================================
        // 心跳。看似多余，实则是长连接能不能活下来的关键。
        //
        // 模型首字节延迟常有数秒，加上召回与 rerank 可能十几秒都没有数据。
        // 这段静默期里，中间任何一层（网关、Nginx、云 LB、企业出口代理）
        // 都可能按空闲超时把连接掐掉，前端表现为"转半天然后断开"。
        //
        // SSE 注释帧（以 ':' 开头）不会触发前端的 message 事件，
        // 纯粹用来让连接上有字节流动。
        // ========================================================
        Flux<ServerSentEvent<Object>> keepAlive = Flux
                .interval(Duration.ofMillis(props.keepAliveMs()))
                .map(i -> ServerSentEvent.builder().comment("keep-alive").<Object>build());

        Flux<ServerSentEvent<Object>> leaseRenewal = activeLease == null
                ? Flux.empty()
                : Flux.interval(Duration.ofMillis(props.quotaRenewalIntervalMs()))
                .concatMap(i -> activeLease.renewAsync()
                        .thenMany(Flux.<ServerSentEvent<Object>>empty())
                        .onErrorResume(error -> {
                            SafeChatLog.error(log, "lease_renewal", error);
                            return activeLease.failAsync()
                                    .onErrorResume(settlementError -> {
                                        SafeChatLog.error(log, "lease_renewal_settlement", settlementError);
                                        return Mono.empty();
                                    })
                                    .thenMany(errorEvent("配额租约已失效，请重试。"));
                        }));

        // ========================================================
        // 合并后靠终止事件结束，而不是靠 body 自己完成。原因有两层：
        //
        // 1) keepAlive 是无限流，mergeWith 要等两条都完成才完成，
        //    所以合并流永远不会自己结束——必须显式 takeUntil。
        //
        // 2) 曾想写 keepAlive.takeUntilOther(body.then())，那是个陷阱：
        //    body.then() 会把 body 再订阅一遍，于是整条 RAG 流水线
        //    执行两次（召回两次、模型调两次、账单两份），
        //    而前端完全看不出异常。冷流不要重复订阅。
        //
        // 这里依赖 ChatService 保证一定会发出 done 或 error 作为收尾，
        // 该保证在 ChatService 中用 concatWith + onErrorResume 强制实现。
        // ========================================================
        return Flux.merge(body, keepAlive, leaseRenewal)
                .takeUntil(sse -> "done".equals(sse.event()) || "error".equals(sse.event()));
    }

    private static Flux<ServerSentEvent<Object>> errorEvent(String message) {
        return Flux.just(ServerSentEvent.builder()
                .event("error")
                .data((Object) new ChatEvent.Error(message))
                .build());
    }
}
