package com.zhishiku.kb.app.document.diff;

import com.zhishiku.kb.app.document.diff.service.BlockDiff;
import com.zhishiku.kb.app.document.diff.service.DiffDeadline;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiffSimilarityTest {
    @Test
    void unrelatedContentsFallBelowConfiguredThresholdAndDeadlineUsesClock() {
        var props = BlockDiffTest.properties();
        var result = new BlockDiff(props).compare(List.of(BlockDiffTest.block(0, "甲乙丙丁")),
                List.of(BlockDiffTest.block(0, "一二三四")), BlockDiffTest.deadline());
        assertThat(result.similarity()).isLessThan(props.minSimilarity());
        var clock = new AtomicLong();
        var deadline = new DiffDeadline(1, () -> clock.addAndGet(100000));
        assertThatThrownBy(() -> new BlockDiff(props).compare(List.of(BlockDiffTest.block(0, "正文".repeat(100))),
                List.of(BlockDiffTest.block(0, "修订".repeat(100))), deadline))
                .isInstanceOf(DiffDeadline.Expired.class);
    }
}
