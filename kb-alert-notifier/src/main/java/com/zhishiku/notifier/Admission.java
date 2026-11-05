package com.zhishiku.notifier;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

final class Admission {
    private final Deque<Long> attempts = new ArrayDeque<>();
    private final LongSupplier ticker;
    private int inflight;

    Admission(LongSupplier ticker) {
        this.ticker = ticker;
    }

    synchronized void enter() {
        if (inflight >= 2) {
            throw new Rejection(503, Rejection.Reason.concurrency);
        }
        long now = ticker.getAsLong();
        while (!attempts.isEmpty() && now - attempts.peekFirst() >= Duration.ofSeconds(60).toNanos()) {
            attempts.removeFirst();
        }
        if (attempts.size() >= 10) {
            throw new Rejection(503, Rejection.Reason.rate);
        }
        attempts.addLast(now);
        inflight++;
    }

    synchronized void exit() {
        inflight--;
    }

    synchronized int inflight() {
        return inflight;
    }
}
