package com.zhishiku.kb.app.document.diff;

import com.zhishiku.kb.app.document.diff.model.SnapshotBlock;
import com.zhishiku.kb.app.document.diff.service.BlockDiff;
import com.zhishiku.kb.app.document.diff.service.DiffDeadline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockDiffTest {
    static DocumentDiffProperties properties() {
        return new DocumentDiffProperties(500000, 10000, 5000, 4, 20000, 250000, 0.3);
    }

    static DiffDeadline deadline() {
        return new DiffDeadline(5000, System::nanoTime);
    }

    static SnapshotBlock block(int index, String text) {
        return new SnapshotBlock(index, "PARAGRAPH", text, "标题", 1, null, null, null, null, null, null);
    }

    @Test
    void modifiesOneSentenceAndKeepsRequestedDirection() {
        var engine = new BlockDiff(properties());
        var result = engine.compare(List.of(block(0, "前言"), block(1, "支付金额为五千元。其余条款保持有效。")),
                List.of(block(0, "前言"), block(1, "支付金额为八千元。其余条款保持有效。")), deadline());
        assertThat(result.operations()).extracting(op -> op.op()).containsExactly("EQUAL", "MODIFIED");
        assertThat(result.operations().get(1).segments()).anySatisfy(segment -> {
            assertThat(segment.type()).isEqualTo("INSERT");
            assertThat(segment.text()).contains("八");
        });
        var reversed = engine.compare(List.of(block(0, "目标新增段落")), List.of(), deadline());
        assertThat(reversed.operations()).extracting(op -> op.op()).containsExactly("REMOVED");
    }

    @Test
    void recognizesExactMovesAndIdenticalVersions() {
        var engine = new BlockDiff(properties());
        var a = List.of(block(0, "甲段落"), block(1, "乙段落"), block(2, "丙段落"));
        var b = List.of(block(0, "乙段落"), block(1, "丙段落"), block(2, "甲段落"));
        var moved = engine.compare(a, b, deadline());
        assertThat(moved.stats().moved()).isEqualTo(1);
        assertThat(moved.stats().added()).isZero();
        assertThat(moved.stats().removed()).isZero();
        assertThat(moved.operations().getLast().base().index()).isZero();
        assertThat(moved.operations().getLast().target().index()).isEqualTo(2);
        var same = engine.compare(a, a, deadline());
        assertThat(same.stats().unchanged()).isEqualTo(3);
        assertThat(same.similarity()).isEqualTo(1);
    }

    @Test
    void pairingBoundAndCancellationStopWork() {
        var props = new DocumentDiffProperties(500000, 10000, 5000, 4, 20000, 1, 0.3);
        var result = new BlockDiff(props).compare(List.of(block(0, "条款甲内容"), block(1, "条款乙内容")),
                List.of(block(0, "条款甲修订"), block(1, "条款乙修订")), deadline());
        assertThat(result.stats().modified()).isZero();
        var expired = deadline();
        expired.cancel();
        assertThatThrownBy(() -> new BlockDiff(properties()).compare(List.of(block(0, "正文")), List.of(), expired))
                .isInstanceOf(DiffDeadline.Expired.class);
    }
}
