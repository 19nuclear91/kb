package com.zhishiku.kb.app.document.model;

import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentSecurityLevelChangeTest {
    @Test
    void unicodeLimitsCountCodePointsAndStripWhitespace() {
        String reason = "\uD83D\uDE00".repeat(500);
        String request = "\uD83D\uDE00".repeat(128);
        var value =
                new DocumentSecurityLevelChange((short) 4, 0L, "\u2003" + reason + "\u2003", request).normalized(null);
        assertThat(value.reason()).isEqualTo(reason);
        assertThat(value.requestId()).isEqualTo(request);
        assertThatThrownBy(() -> new DocumentSecurityLevelChange((short) 4, 0L, reason + "多", request).normalized(null))
                .isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> new DocumentSecurityLevelChange((short) 4, 0L, reason, request + "多").normalized(null))
                .isInstanceOf(IdentityException.class);
    }

    @Test
    void emptyHeaderDoesNotOverrideExplicitBodyId() {
        assertThatThrownBy(() -> new DocumentSecurityLevelChange((short) 1, 0L, "原因", "r").normalized(" "))
                .isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> new DocumentSecurityLevelChange((short) 1, 0L, "原因", null).normalized(null))
                .isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> new DocumentSecurityLevelChange((short) 1, null, "原因", "r").normalized(null))
                .isInstanceOf(IdentityException.class);
        assertThat(new DocumentSecurityLevelChange((short) 1, 0L, "原因", null).normalized(" header ").requestId())
                .isEqualTo("header");
    }
}
