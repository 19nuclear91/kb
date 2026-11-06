package com.zhishiku.kb.app.auth.lifecycle;

import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DingTalkStreamConnectionTest {
    @Test
    void fragmentedAndUtf8OversizeAreObservedBeforeProtocolDispatch() {
        var rejected = new java.util.ArrayList<DingTalkInputRejected.Reason>();
        var socket = mock(WebSocket.class);
        var frames = new DingTalkStreamConnection.Frames(5, 2000, value -> {
            throw new AssertionError("超限输入不应分派");
        }, rejected::add);
        frames.onText(socket, "1234", false);
        frames.onText(socket, "56", true);
        frames.onText(socket, "中文", true);
        assertThat(rejected).containsExactly(DingTalkInputRejected.Reason.MESSAGE_LIMIT, DingTalkInputRejected.Reason.MESSAGE_LIMIT);
        verify(socket, times(2)).abort();
    }
    @Test
    void connectionTickRechecksReadinessAndClosesBothCurrentAndReplacementResources() {
        var readiness = mock(LifecycleReadiness.class);
        var protocol = mock(DingTalkStreamProtocol.class);
        var dialer = mock(DingTalkStreamConnection.Dialer.class);
        var timer = mock(ScheduledExecutorService.class);
        var first = mock(DingTalkStreamConnection.Channel.class);
        var second = mock(DingTalkStreamConnection.Channel.class);
        when(dialer.open(any())).thenReturn(first, second);
        when(first.active()).thenReturn(true);
        var connection = new DingTalkStreamConnection(DingTalkLifecycleSettingsTest.configured(true), readiness,
                protocol, dialer, () -> timer);
        connection.start();
        verify(timer).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(5L), eq(TimeUnit.SECONDS));
        connection.connectIfNeeded();
        connection.connectIfNeeded();
        verify(dialer, times(1)).open(any());
        when(first.active()).thenReturn(false);
        connection.connectIfNeeded();
        verify(dialer, times(2)).open(any());
        verify(first).abort();
        var order = inOrder(readiness, dialer);
        order.verify(readiness, times(2)).requireReady();
        order.verify(dialer).open(any());
        order.verify(readiness).requireReady();
        order.verify(dialer).open(any());
        connection.stop();
        verify(second).abort();
        verify(timer).shutdownNow();
        verify(dialer).close();
        assertThat(connection.isRunning()).isFalse();
    }

    @Test
    void disabledSubscriptionNeverDialsOrSchedules() {
        var readiness = mock(LifecycleReadiness.class);
        var dialer = mock(DingTalkStreamConnection.Dialer.class);
        var timer = mock(ScheduledExecutorService.class);
        var connection = new DingTalkStreamConnection(DingTalkLifecycleSettingsTest.configured(false), readiness,
                mock(DingTalkStreamProtocol.class), dialer, () -> timer);
        connection.start();
        connection.connectIfNeeded();
        verifyNoInteractions(readiness, dialer, timer);
    }

    @Test
    void fragmentationAndAckBackpressureAreBoundedAndLateFailureClosesSocket() {
        var socket = mock(WebSocket.class);
        var response = new CompletableFuture<DingTalkStreamProtocol.Reply>();
        var seen = new AtomicReference<String>();
        Function<String, CompletableFuture<DingTalkStreamProtocol.Reply>> receiver = text -> {
            seen.set(text);
            return response;
        };
        var frames = new DingTalkStreamConnection.Frames(10, 2000, receiver);
        frames.onText(socket, "123", false);
        assertThat(seen).hasValue(null);
        frames.onText(socket, "45", true);
        assertThat(seen).hasValue("12345");
        verify(socket, times(1)).request(1);
        var sending = new CompletableFuture<WebSocket>();
        when(socket.sendText("ack", true)).thenReturn(sending);
        response.complete(new DingTalkStreamProtocol.Reply("ack", false));
        verify(socket, times(1)).request(1);
        sending.complete(socket);
        verify(socket, times(2)).request(1);
        frames.onText(socket, "12345678901", true);
        verify(socket).abort();
        verify(socket, never()).request(2);
    }

    @Test
    void shutdownDuringConnectionAttemptNeverInstallsLateSocket() {
        var dialer = mock(DingTalkStreamConnection.Dialer.class);
        var timer = mock(ScheduledExecutorService.class);
        var late = mock(DingTalkStreamConnection.Channel.class);
        var connection = new DingTalkStreamConnection(DingTalkLifecycleSettingsTest.configured(true),
                mock(LifecycleReadiness.class), mock(DingTalkStreamProtocol.class), dialer, () -> timer);
        when(dialer.open(any())).thenAnswer(call -> {
            connection.stop();
            return late;
        });
        connection.start();
        connection.connectIfNeeded();
        verify(late).abort();
        assertThat(connection.isRunning()).isFalse();
    }
}
