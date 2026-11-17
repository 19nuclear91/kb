package com.zhishiku.kb.app.document.diff;

import com.zhishiku.kb.app.document.diff.model.DiffViews.Operation;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Side;
import com.zhishiku.kb.app.document.diff.service.ValueChangeExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValueChangeExtractorTest {
    private Operation op(String type, String before, String after) {
        return new Operation(type, "PARAGRAPH", "标题", new Side(0, 1, before), new Side(0, 1, after),
                List.of(), false, null);
    }

    @Test
    void amountsNormalizeWidthThousandsAndIgnoreAddedAndMoved() {
        var extractor = new ValueChangeExtractor();
        var changes = extractor.extract(List.of(op("MODIFIED", "支付5,000 元", "支付8,000 元")), BlockDiffTest.deadline());
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().before()).isEqualTo("5,000 元");
        assertThat(changes.getFirst().after()).isEqualTo("8,000 元");
        assertThat(extractor.extract(List.of(op("MODIFIED", "５０００元", "5,000元"),
                op("ADDED", "", "2026年1月1日"), op("MOVED", "5000元", "8000元"),
                op("MODIFIED", "10万人", "20万人")), BlockDiffTest.deadline())).isEmpty();
    }

    @Test
    void datesClausesAndOccurrencesPairInOriginalOrderIncludingOneSidedValues() {
        var extractor = new ValueChangeExtractor();
        for (String date : List.of("2026-1-2", "2026/01/02", "2026.1.2", "2026年1月2日")) {
            assertThat(extractor.extract(List.of(op("MODIFIED", date, "2026年01月02日")), BlockDiffTest.deadline()))
                    .isEmpty();
        }
        for (String date : List.of("2026年1月", "1月2日")) {
            assertThat(extractor.extract(List.of(op("MODIFIED", date, "")), BlockDiffTest.deadline()))
                    .singleElement().satisfies(change -> assertThat(change.after()).isNull());
        }
        assertThat(extractor.extract(List.of(op("MODIFIED", "第十二条", "第12条")), BlockDiffTest.deadline())).isEmpty();
        var changes = extractor.extract(List.of(op("MODIFIED", "5% 6% 7%", "8% 9%")), BlockDiffTest.deadline());
        assertThat(changes).extracting(change -> change.before()).containsExactly("5%", "6%", "7%");
        assertThat(changes).extracting(change -> change.after()).containsExactly("8%", "9%", null);
        assertThat(extractor.extract(List.of(op("MODIFIED", "", "第十五章")), BlockDiffTest.deadline()))
                .singleElement().satisfies(change -> assertThat(change.before()).isNull());
    }
}
