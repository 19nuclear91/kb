package com.zhishiku.kb.app.document.changenote;

import com.zhishiku.kb.app.document.changenote.model.ChangeNoteInput;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeNoteInputTest {
    private ChangeNoteInput parse(String raw) {
        return ChangeNoteInput.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void preservesNewlinesTrimsWhitespaceAndCountsCodePoints() {
        assertThat(parse("{\"text\":\"  第一段\\n第二段\\t甲  \",\"reason\":null,\"expectedNoteVersion\":0}").text())
                .isEqualTo("第一段\n第二段\t甲");
        assertThat(new ChangeNoteInput("😀".repeat(2000), null, 0).text()).hasSize(4000);
        assertThat(ChangeNoteInput.requestId("  request  ")).isEqualTo("request");
    }

    @Test
    void rejectsAllInvalidBodiesWithoutEchoingContent() {
        for (String raw : List.of("{}", "{\"text\":null,\"reason\":null}",
                "{\"text\":\"   \",\"reason\":null,\"expectedNoteVersion\":0}",
                "{\"text\":null,\"reason\":null,\"expectedNoteVersion\":0,\"extra\":1}",
                "{\"text\":null,\"text\":null,\"reason\":null,\"expectedNoteVersion\":0}",
                "{\"text\":null,\"reason\":null,\"expectedNoteVersion\":0} {}",
                "{\"text\":\"\\u0000\",\"reason\":null,\"expectedNoteVersion\":0}",
                "{\"text\":\"\\uD800\",\"reason\":null,\"expectedNoteVersion\":0}",
                "{\"text\":true,\"reason\":null,\"expectedNoteVersion\":0}",
                "{\"text\":null,\"reason\":null,\"expectedNoteVersion\":0.1}")) {
            assertThatThrownBy(() -> parse(raw)).isInstanceOfSatisfying(ChangeNoteException.class,
                    failure -> assertThat(failure.reason().status()).isEqualTo(400));
        }
        for (String value : List.of("甲".repeat(2001), "\u0001", "\uDC00", "\u00a0")) {
            assertThatThrownBy(() -> new ChangeNoteInput(value, null, 0)).isInstanceOf(ChangeNoteException.class);
            assertThatThrownBy(() -> new ChangeNoteInput(null, value, 0)).isInstanceOf(ChangeNoteException.class);
        }
        assertThatThrownBy(() -> ChangeNoteInput.parse(new ByteArrayInputStream(new byte[32769])))
                .isInstanceOfSatisfying(ChangeNoteException.class, failure -> assertThat(failure.reason().status()).isEqualTo(413));
    }
}
