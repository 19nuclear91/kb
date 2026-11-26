package com.zhishiku.kb.app.service.impl.answercard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.config.AnswerCardSearchProperties;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerCardSearchExecutorTest {
    @Test
    void virtualClockNeverExtendsBudgetAndEachStatementIsBounded() {
        var clock = new AtomicLong();
        try (var budget = new AnswerCardSearchBudget(5000, clock::get)) {
            budget.install();
            assertThat(AnswerCardSearchBudget.current()).isSameAs(budget);
            assertThat(budget.statementMillis()).isEqualTo(3000);
            clock.set(4_900_000_000L);
            assertThat(budget.statementMillis()).isEqualTo(100);
            clock.set(5_000_000_000L);
            assertThatThrownBy(budget::remainingNanos).isInstanceOf(AnswerCardFailure.class);
        }
        assertThat(AnswerCardSearchBudget.current()).isNull();
    }

    @Test
    void capacityRejectsWithoutQueueAndWorkCompletionReleasesSlot() throws Exception {
        var executor = new AnswerCardSearchExecutor(new AnswerCardSearchProperties(2, 1, 5000, 262144), new ObjectMapper());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var first = CompletableFuture.supplyAsync(() -> executor.execute(7, () -> {
                entered.countDown();
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException();
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException();
                }
                return List.of();
            }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> executor.execute(7, List::of)).isInstanceOfSatisfying(AnswerCardFailure.class,
                    failure -> assertThat(failure.status()).isEqualTo(429));
            assertThat(new ObjectMapper().readTree(executor.execute(8, List::of)).path("code").intValue()).isEqualTo(200);
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isNotEmpty();
        } finally {
            release.countDown();
            executor.close();
        }
    }

    @Test
    void responseLimitAndDependencyFailureAreNeverSuccessfulEmptyResults() {
        var executor = new AnswerCardSearchExecutor(new AnswerCardSearchProperties(2, 1, 5000, 128), new ObjectMapper());
        try {
            assertThatThrownBy(() -> executor.execute(7, () -> "秘密".repeat(100)))
                    .isInstanceOfSatisfying(AnswerCardFailure.class, failure -> assertThat(failure.status()).isEqualTo(503));
            assertThatThrownBy(() -> executor.execute(8, () -> {
                throw AnswerCardFailure.unavailable();
            })).isInstanceOf(AnswerCardFailure.class);
        } finally {
            executor.close();
        }
        assertThatThrownBy(() -> new AnswerCardSearchProperties(9, 2, 5000, 262144)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnswerCardSearchProperties(8, 2, 5001, 262144)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellingCallerInterruptsWorkAndRetainsCapacityUntilWorkerExits() throws Exception {
        var executor = new AnswerCardSearchExecutor(new AnswerCardSearchProperties(1, 1, 5000, 262144), new ObjectMapper());
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var exit = new CountDownLatch(1);
        var callerDone = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread caller = new Thread(() -> {
            try {
                executor.execute(7, () -> {
                    entered.countDown();
                    try {
                        exit.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException expected) {
                        interrupted.countDown();
                        try {
                            exit.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException again) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return List.of();
                });
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                callerDone.countDown();
            }
        });
        try {
            caller.start();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            assertThat(callerDone.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isInstanceOf(AnswerCardFailure.class);
            assertThatThrownBy(() -> executor.execute(8, List::of)).isInstanceOfSatisfying(AnswerCardFailure.class,
                    value -> assertThat(value.status()).isEqualTo(429));
        } finally {
            exit.countDown();
            executor.close();
        }
    }
}
