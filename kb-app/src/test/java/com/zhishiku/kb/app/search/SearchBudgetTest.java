package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.config.SearchExecutionProperties;
import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.model.SearchFailure;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchBudgetTest {
    @Test
    void earlierWorkReducesLaterWindowsAndReservesDatabaseTime() {
        var clock = new AtomicLong();
        var budget = new SearchBudget(SearchExecutionProperties.defaults(), clock::get);
        clock.set(3_000_000_000L);
        assertThat(budget.available(SearchBudget.Stage.EMBEDDING)).isEqualTo(1_500_000_000L);
        var embedding = budget.begin(SearchBudget.Stage.EMBEDDING);
        clock.set(4_000_000_000L);
        assertThat(embedding.remainingNanos()).isEqualTo(500_000_000L);
        clock.set(4_500_000_000L);
        assertThat(embedding.remainingNanos()).isZero();
        assertThat(budget.available(SearchBudget.Stage.EMBEDDING)).isZero();
        assertThat(budget.available(SearchBudget.Stage.RETRIEVAL)).isEqualTo(3_000_000_000L);
        clock.set(7_000_000_000L);
        assertThat(budget.available(SearchBudget.Stage.RERANK)).isEqualTo(500_000_000L);
        assertThat(budget.begin(SearchBudget.Stage.RETRIEVAL).queryTimeoutSeconds()).isOne();
    }

    @Test
    void responseReserveCannotStartNewWorkOrAcceptLateSuccess() {
        var clock = new AtomicLong();
        var budget = new SearchBudget(SearchExecutionProperties.defaults(), clock::get);
        clock.set(7_500_000_000L);
        assertThat(budget.available(SearchBudget.Stage.RERANK)).isZero();
        assertThat(budget.total().remainingNanos()).isEqualTo(500_000_000L);
        clock.set(8_000_000_000L);
        assertThatThrownBy(() -> budget.total().check()).isInstanceOf(SearchFailure.class);
    }

    @Test
    void monotonicCounterWrapDoesNotResetBudget() {
        var clock = new AtomicLong(Long.MAX_VALUE - 1_000_000_000L);
        var budget = new SearchBudget(SearchExecutionProperties.defaults(), clock::get);
        clock.addAndGet(2_000_000_000L);
        assertThat(budget.total().remainingNanos()).isEqualTo(6_000_000_000L);
    }
}
