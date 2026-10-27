package com.zhishiku.kb.ai.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 流式接口的行为约束测试。
 *
 * <p>这里只测三件容易写错且出错后很难察觉的事，不测业务内容：
 * 流会不会结束、业务流被订阅几次、心跳会不会误伤终止。
 * 三者在手工联调时都不容易暴露——前端看起来"能用"，
 * 问题要到并发上量或账单出来才发现。
 *
 * <p>刻意不起 Spring 上下文：被测的是 Reactor 的组装逻辑，
 * 直接 new 出来测最快也最稳。
 */
class ChatControllerTest {

    /**
     * 心跳给足够大，保证测试里不会有 keep-alive 帧混进来干扰断言。
     */
    private static final AiProperties PROPS =
            new AiProperties("http://localhost:8081", 8, 60_000L, 60);

    @Test
    @DisplayName("收到 done 事件后 SSE 流必须结束，不能被无限心跳拖住")
    void streamTerminatesOnDone() {
        ChatService service = mock(ChatService.class);
        when(service.chat(any(), any())).thenReturn(Flux.just(
                new ChatEvent.Delta("你"),
                new ChatEvent.Delta("好"),
                new ChatEvent.Done(0, 0, 12L)));

        ChatController controller = new ChatController(service, PROPS);

        StepVerifier.create(controller.chat("测试", "1"))
                .expectNextMatches(sse -> "delta".equals(sse.event()))
                .expectNextMatches(sse -> "delta".equals(sse.event()))
                .expectNextMatches(sse -> "done".equals(sse.event()))
                // 关键断言：流要真的完成。心跳是无限流，
                // 若终止条件写错，这里会一直等到超时。
                .verifyComplete();
    }

    @Test
    @DisplayName("error 事件同样要结束流，否则前端会一直挂着等")
    void streamTerminatesOnError() {
        ChatService service = mock(ChatService.class);
        when(service.chat(any(), any())).thenReturn(Flux.just(
                new ChatEvent.Error("处理请求时出错，请稍后重试。")));

        ChatController controller = new ChatController(service, PROPS);

        StepVerifier.create(controller.chat("测试", "1"))
                .expectNextMatches(sse -> "error".equals(sse.event()))
                .verifyComplete();
    }

    @Test
    @DisplayName("业务流只能被订阅一次——重复订阅会让整条 RAG 流水线跑两遍")
    void businessFluxIsSubscribedExactlyOnce() {
        // 这是一个回归测试。原先的写法是
        //     body.mergeWith(keepAlive.takeUntilOther(body.then()))
        // body.then() 会把冷流 body 再订阅一遍，于是召回两次、模型调两次、
        // 账单两份，而前端输出完全正常，肉眼绝对看不出来。
        AtomicInteger subscriptions = new AtomicInteger();

        ChatService service = mock(ChatService.class);
        when(service.chat(any(), any())).thenReturn(
                Flux.defer(() -> {
                    subscriptions.incrementAndGet();
                    return Flux.just(
                            new ChatEvent.Delta("x"),
                            new ChatEvent.Done(0, 0, 1L));
                }));

        ChatController controller = new ChatController(service, PROPS);

        StepVerifier.create(controller.chat("测试", "1"))
                .expectNextCount(2)
                .verifyComplete();

        assertThat(subscriptions.get())
                .as("RAG 流水线被订阅的次数；大于 1 意味着召回与模型调用都被执行了多次")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("模型静默期内应持续发出心跳注释帧，避免中间层按空闲超时断链")
    void keepAliveFramesAreEmittedWhileWaiting() {
        AiProperties fastHeartbeat =
                new AiProperties("http://localhost:8081", 8, 50L, 60);

        ChatService service = mock(ChatService.class);
        // 模拟"召回 + 首字节"很慢的真实情况：500ms 内一个字都没有。
        when(service.chat(any(), any())).thenReturn(
                Flux.just((ChatEvent) new ChatEvent.Done(0, 0, 500L))
                        .delayElements(Duration.ofMillis(500)));

        ChatController controller = new ChatController(service, fastHeartbeat);

        Long keepAlives = controller.chat("测试", "1")
                .takeUntil(sse -> "done".equals(sse.event()))
                .filter(sse -> sse.comment() != null)
                .count()
                .block(Duration.ofSeconds(5));

        assertThat(keepAlives)
                .as("静默期内发出的心跳帧数；为 0 说明长时间无数据时连接上没有任何字节，"
                        + "会被网关或代理按空闲超时掐断")
                .isNotNull()
                .isGreaterThan(0L);
    }

    @Test
    @DisplayName("ServerSentEvent 的 event 名称必须与 ChatEvent.type() 一致，前端按它分派")
    void eventNamesMatchContract() {
        assertThat(new ChatEvent.Delta("a").type()).isEqualTo("delta");
        assertThat(new ChatEvent.Citation(1L, "t", 1, 2L).type()).isEqualTo("citation");
        assertThat(new ChatEvent.Done(0, 0, 0L).type()).isEqualTo("done");
        assertThat(new ChatEvent.Error("e").type()).isEqualTo("error");
    }

    @Test
    @DisplayName("缺少 app-base-url 时必须启动即失败，而不是运行期才发现取不到语料")
    void missingAppBaseUrlFailsFast() {
        try {
            new AiProperties("  ", 8, 1000L, 60);
            throw new AssertionError("应当抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("app-base-url");
        }
    }

    /**
     * 辅助：确认 SSE 注释帧确实不携带 data，不会被前端当成消息处理。
     */
    @Test
    @DisplayName("心跳帧不得携带 data，否则前端会把它当成一条空消息渲染")
    void keepAliveCarriesNoData() {
        ServerSentEvent<Object> keepAlive =
                ServerSentEvent.builder().comment("keep-alive").build();
        assertThat(keepAlive.data()).isNull();
        assertThat(keepAlive.event()).isNull();
    }

    @Test
    @DisplayName("透传给 kb-app 的 Sa-Token 头名称必须固定")
    void tokenHeaderIsConfigured() {
        assertThat(RetrievalClient.TOKEN_HEADER)
                .as("透传给 kb-app 的 Sa-Token 请求头名称")
                .isEqualTo("satoken");
    }

    @Test
    @DisplayName("持久化配额拒绝时应返回 SSE error，不能启动检索流")
    void quotaRejectionIsStreamError() {
        ChatService service = mock(ChatService.class);
        ChatQuotaAuditService quota = mock(ChatQuotaAuditService.class);
        when(quota.acquire(any(), anyInt())).thenReturn(Mono.error(
                new ChatQuotaAuditService.QuotaExceededException("部门配额已用尽")));

        ChatController controller = new ChatController(service, PROPS, quota);

        StepVerifier.create(controller.chat("测试", "token"))
                .expectNextMatches(sse -> "error".equals(sse.event())
                        && sse.data() instanceof ChatEvent.Error error
                        && error.message().contains("配额"))
                .verifyComplete();
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("主体被拒（停用/未认证）时返回 SSE error 事件而非 500，文案来自异常本身")
    void rejectedPrincipalIsStreamErrorNot500() {
        ChatService service = mock(ChatService.class);
        ChatQuotaAuditService quota = mock(ChatQuotaAuditService.class);
        when(quota.acquire(any(), anyInt())).thenReturn(Mono.error(
                new QuotaPrincipalRejectedException("当前用户或部门已停用，无法使用 AI 配额")));

        ChatController controller = new ChatController(service, PROPS, quota);

        StepVerifier.create(controller.chat("测试", "token"))
                .expectNextMatches(sse -> "error".equals(sse.event())
                        && sse.data() instanceof ChatEvent.Error error
                        && error.message().equals("当前用户或部门已停用，无法使用 AI 配额"))
                // 流以完成收尾而非错误信号：错误信号会变成 HTTP 500，
                // error 事件让前端在已建立的 SSE 连接上拿到可读的拒绝原因。
                .verifyComplete();
        verifyNoInteractions(service);
    }

    @Test
    void versionedCitationIsUnchangedAndUsageIsSettledBeforeDone() {
        ChatService service = mock(ChatService.class);
        ChatQuotaAuditService quota = mock(ChatQuotaAuditService.class);
        ChatQuotaAuditService.Lease lease = mock(ChatQuotaAuditService.Lease.class);
        ChatEvent.Citation citation = versionedCitation();
        when(service.chat("测试", "token")).thenReturn(Flux.just(citation,
                new ChatEvent.Delta("答"), new ChatEvent.Done(37, 11, 12L)));
        when(quota.acquire("token", 2)).thenReturn(Mono.just(lease));
        AtomicInteger settlements = new AtomicInteger();
        when(lease.completeAsync(37, 11)).thenReturn(Mono.fromRunnable(settlements::incrementAndGet));
        when(lease.cancelAsync()).thenReturn(Mono.empty());
        ChatController controller = new ChatController(service, PROPS, quota);
        Flux<ServerSentEvent<Object>> stream = controller.chat("测试", "token");
        verifyNoInteractions(service, quota);

        StepVerifier.create(stream)
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("citation");
                    assertThat(sse.data()).isSameAs(citation);
                    assertThat(settlements.get()).isZero();
                })
                .expectNextMatches(sse -> "delta".equals(sse.event()))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("done");
                    assertThat(sse.data()).isEqualTo(new ChatEvent.Done(37, 11, 12L));
                    assertThat(settlements.get()).isEqualTo(1);
                })
                .verifyComplete();

        verify(quota).acquire("token", 2);
        verify(service).chat("测试", "token");
        verify(lease).completeAsync(37, 11);
        verify(lease, never()).failAsync();
    }

    @Test
    void errorAfterCitationSettlesFailureBeforeSendingTerminalEvent() {
        ChatService service = mock(ChatService.class);
        ChatQuotaAuditService quota = mock(ChatQuotaAuditService.class);
        ChatQuotaAuditService.Lease lease = mock(ChatQuotaAuditService.Lease.class);
        ChatEvent.Citation citation = versionedCitation();
        ChatEvent.Error error = new ChatEvent.Error("处理请求时出错，请稍后重试。");
        when(service.chat(any(), any())).thenReturn(Flux.just(citation, error));
        when(quota.acquire(any(), anyInt())).thenReturn(Mono.just(lease));
        AtomicBoolean settled = new AtomicBoolean();
        when(lease.failAsync()).thenReturn(Mono.fromRunnable(() -> settled.set(true)));
        when(lease.cancelAsync()).thenReturn(Mono.empty());
        ChatController controller = new ChatController(service, PROPS, quota);

        StepVerifier.create(controller.chat("测试", "token"))
                .expectNextMatches(sse -> "citation".equals(sse.event()) && sse.data() == citation)
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("error");
                    assertThat(sse.data()).isSameAs(error);
                    assertThat(settled.get()).isTrue();
                })
                .verifyComplete();

        verify(lease).failAsync();
        verify(lease, never()).completeAsync(anyInt(), anyInt());
    }

    @Test
    void cancellationReleasesLeaseAndCancelsBusinessStreamAfterCitation() {
        ChatService service = mock(ChatService.class);
        ChatQuotaAuditService quota = mock(ChatQuotaAuditService.class);
        ChatQuotaAuditService.Lease lease = mock(ChatQuotaAuditService.Lease.class);
        ChatEvent.Citation citation = versionedCitation();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(service.chat(any(), any())).thenReturn(Flux.<ChatEvent>just(citation)
                .concatWith(Flux.never()).doOnCancel(() -> cancelled.set(true)));
        when(quota.acquire(any(), anyInt())).thenReturn(Mono.just(lease));
        when(lease.cancelAsync()).thenReturn(Mono.empty());
        ChatController controller = new ChatController(service, PROPS, quota);

        StepVerifier.create(controller.chat("测试", "token"))
                .expectNextMatches(sse -> "citation".equals(sse.event()) && sse.data() == citation)
                .thenCancel()
                .verify();

        assertThat(cancelled.get()).isTrue();
        verify(lease).cancelAsync();
        verify(lease, never()).completeAsync(anyInt(), anyInt());
        verify(lease, never()).failAsync();
    }

    private static ChatEvent.Citation versionedCitation() {
        return ChatEvent.Citation.from(new RetrievalClient.Citation(10L, "说明", 2, 20L,
                "裁剪后的正文", 30L, 7, 4, "权限", "PARAGRAPH", 10, 18, 120, 340,
                "section:access", true));
    }
}
