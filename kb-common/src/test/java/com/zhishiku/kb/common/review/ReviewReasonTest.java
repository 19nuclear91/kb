package com.zhishiku.kb.common.review;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewReasonTest {
    @Test
    void validatesUnicodeCodePointsNotUtf16UnitsAndNormalizesDigestInput() {
        String emoji = "\uD83D\uDE00";
        assertThat(ReviewReason.normalize(emoji.repeat(2000))).hasSize(4000);
        assertThatThrownBy(() -> ReviewReason.normalize(emoji.repeat(2001))).isInstanceOf(ReviewFailure.class);
        assertThat(ReviewReason.normalize(" \r\n e\u0301\r\n理由 \r\n")).isEqualTo("é\n理由");
        assertThat(ReviewCommandBinding.digest(ReviewReason.normalize("e\u0301")))
                .isEqualTo(ReviewCommandBinding.digest(ReviewReason.normalize("é")));
    }

    @Test
    void rejectsEmptyControlAndBrokenUnicodeButTreatsMarkupAsLiteralText() {
        for (String invalid : new String[]{"", " \r\n\t ", "x\u0000y", "x\u001By", "\uD800"}) {
            assertThatThrownBy(() -> ReviewReason.normalize(invalid)).isInstanceOf(ReviewFailure.class);
        }
        assertThatThrownBy(() -> ReviewReason.normalize(null)).isInstanceOf(ReviewFailure.class);
        assertThat(ReviewReason.normalize("<b>仅是文字</b>\n\t不执行 HTML"))
                .isEqualTo("<b>仅是文字</b>\n\t不执行 HTML");
    }
}
