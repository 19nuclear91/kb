package com.zhishiku.kb.common.office;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficeProtocolTest {
    private static final String KEY = "kb-" + "a".repeat(64);

    @Test
    void onlyFixedVersionReadResourcesAreAllowed() {
        assertThat(OfficeProtocol.resource("GET", "/web-apps/apps/api/documents/api.js", KEY))
                .isEqualTo(OfficeProtocol.Resource.STATIC);
        assertThat(OfficeProtocol.resource("GET", "/10.0.0-build/web-apps/apps/documenteditor/main/index.html", KEY))
                .isEqualTo(OfficeProtocol.Resource.STATIC);
        assertThat(OfficeProtocol.resource("GET", "/cache/files/data/" + KEY + "/Editor.bin/Editor.bin", KEY))
                .isEqualTo(OfficeProtocol.Resource.CACHE);
        assertThat(OfficeProtocol.resource("POST", "/doc/" + KEY + "/c/", KEY))
                .isEqualTo(OfficeProtocol.Resource.SOCKET);
    }

    @Test
    void canonicalPathsKeysAndReadOnlyRoutesAreEnforced() {
        for (String path : List.of("/converter", "/downloadfile/" + KEY, "/downloadas/" + KEY,
                "/savefile/" + KEY, "/printfile/" + KEY + "/file.pdf", "/coauthoring/CommandService.ashx",
                "/web-apps/../cache/file", "/web-apps/%2e%2e/cache/file", "/web-apps//api.js",
                "/web-apps\\api.js", "/web-apps/app.js?url=https://evil.invalid", "/internal/source",
                "/cache/files/data/" + KEY + "/origin.docx/origin.docx",
                "/cache/files/data/kb-" + "b".repeat(64) + "/Editor.bin/Editor.bin")) {
            assertThat(OfficeProtocol.resource("GET", path, KEY)).isEqualTo(OfficeProtocol.Resource.DENIED);
        }
        assertThat(OfficeProtocol.resource("POST", "/web-apps/apps/api/documents/api.js", KEY))
                .isEqualTo(OfficeProtocol.Resource.DENIED);
        assertThat(OfficeProtocol.resource("GET", "/doc/" + KEY + "/c/", "../other"))
                .isEqualTo(OfficeProtocol.Resource.DENIED);
    }

    @Test
    void modernFormatsAndOpaqueIdentifiersAreStrict() {
        assertThat(OfficeProtocol.extension("财务.XLSX")).isEqualTo("xlsx");
        assertThat(OfficeProtocol.documentType("xlsx")).isEqualTo("cell");
        assertThatThrownBy(() -> OfficeProtocol.documentType("xls")).isInstanceOf(IllegalArgumentException.class);
        assertThat(OfficeProtocol.validSessionId("a".repeat(32))).isTrue();
        assertThat(OfficeProtocol.validSessionId("a".repeat(33))).isFalse();
        assertThat(OfficeProtocol.CLIENT_MESSAGES).doesNotContain("saveChanges", "forceSaveStart", "rpc", "message");
        assertThat(OfficeProtocol.OPEN_COMMANDS).doesNotContain("setpassword", "changedocinfo", "imgurls");
    }
}
