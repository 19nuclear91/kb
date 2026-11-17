package com.zhishiku.kb.app.document.diff;

import com.zhishiku.kb.app.document.diff.service.BlockDiff;
import com.zhishiku.kb.app.document.diff.service.InlineDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class InlineDiffTest {
    @Test
    void preservesBothSidesIncludingSupplementaryCharactersAndMergesTinyEqualIslands() {
        var result = InlineDiff.compare("开始😀甲中乙结束", "开始😀丙中丁结束", BlockDiffTest.deadline());
        assertThat(result).noneSatisfy(segment -> {
            assertThat(segment.type()).isEqualTo("EQUAL");
            assertThat(segment.text()).isEqualTo("中");
        });
        assertThat(result.stream().filter(segment -> !"INSERT".equals(segment.type())).map(segment -> segment.text())
                .collect(Collectors.joining())).isEqualTo("开始😀甲中乙结束");
        assertThat(result.stream().filter(segment -> !"DELETE".equals(segment.type())).map(segment -> segment.text())
                .collect(Collectors.joining())).isEqualTo("开始😀丙中丁结束");
    }

    @Test
    void randomizedEditsReconstructBothInputs() {
        var random = new Random(17);
        for (int trial = 0; trial < 500; trial++) {
            String before = random.ints(random.nextInt(40), 97, 102).mapToObj(value -> String.valueOf((char) value))
                    .collect(Collectors.joining());
            String after = random.ints(random.nextInt(40), 97, 102).mapToObj(value -> String.valueOf((char) value))
                    .collect(Collectors.joining());
            var result = InlineDiff.compare(before, after, BlockDiffTest.deadline());
            assertThat(result.stream().filter(segment -> !"INSERT".equals(segment.type())).map(segment -> segment.text())
                    .collect(Collectors.joining())).isEqualTo(before);
            assertThat(result.stream().filter(segment -> !"DELETE".equals(segment.type())).map(segment -> segment.text())
                    .collect(Collectors.joining())).isEqualTo(after);
        }
    }

    @Test
    void longModifiedBlocksOmitInlineButRetainFullText() {
        String before = "共同正文".repeat(6000) + "甲";
        String after = "共同正文".repeat(6000) + "乙";
        var result = new BlockDiff(BlockDiffTest.properties()).compare(List.of(BlockDiffTest.block(0, before)),
                List.of(BlockDiffTest.block(0, after)), BlockDiffTest.deadline());
        var op = result.operations().getFirst();
        assertThat(op.op()).isEqualTo("MODIFIED");
        assertThat(op.segmentsOmitted()).isTrue();
        assertThat(op.segments()).isEmpty();
        assertThat(op.base().text()).isEqualTo(before);
        assertThat(op.target().text()).isEqualTo(after);
    }
}
