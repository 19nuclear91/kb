package com.zhishiku.kb.app.document.imports;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.document.imports.model.ImportModels.BatchState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Counts;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Manifest;
import com.zhishiku.kb.app.document.imports.service.ImportException;
import com.zhishiku.kb.app.document.imports.support.ImportInput;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportInputTest {
    private static final String FILE = "{\"itemKey\":\"a\",\"fileName\":\" x.txt \",\"sizeBytes\":5,\"sha256\":\"" + "A".repeat(64) + "\"}";

    @Test
    void normalizesCompleteIntentAndPreservesLargeIds() {
        Manifest value = manifest(FILE);
        assertThat(value.spaceId()).isEqualTo(Long.MAX_VALUE);
        assertThat(value.items().getFirst().title()).isEqualTo("x.txt");
        assertThat(value.items().getFirst().sha256()).isEqualTo("a".repeat(64));
        assertThat(value.digest()).isEqualTo(manifest(FILE.replace(" x.txt ", "x.txt")
                .replace("A".repeat(64), "a".repeat(64))).digest());
        assertThat(value.digest()).isNotEqualTo(manifest(FILE.replace("x.txt", "y.txt")).digest());
        assertThatThrownBy(() -> value.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ImportInput.id("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        assertThat(ImportInput.revision("0")).isZero();
    }

    @Test
    void rejectsDuplicateKeysUnknownFieldsAndBrokenJson() {
        for (String json : List.of("{} {}", "[]", "null", "{", "", "{\"a\":1,\"a\":2}")) {
            assertThatThrownBy(() -> body(json)).isInstanceOf(ImportException.class);
        }
        assertThatThrownBy(() -> manifest(FILE + "," + FILE)).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> manifest(FILE.replace("\"sizeBytes\"", "\"other\""))).isInstanceOf(ImportException.class);
        ObjectNode extra = body("{\"spaceId\":1,\"securityLevel\":0,\"items\":[" + FILE + "],\"ownerId\":1}");
        assertThatThrownBy(() -> ImportInput.manifest(extra, 100, 100, 100)).isInstanceOf(ImportException.class);
    }

    @Test
    void rejectsInvalidNumbersAndHashes() {
        for (String value : List.of("0", "-1", "1.0", "\"5\"", "9223372036854775808", "null")) {
            assertThatThrownBy(() -> manifest(FILE.replace(":5,", ":" + value + ","))).isInstanceOf(ImportException.class);
        }
        assertThatThrownBy(() -> manifest(FILE.replace("A".repeat(64), "secret"))).hasMessage("IMPORT_INVALID_INPUT");
        assertThatThrownBy(() -> ImportInput.id("9223372036854775808")).isInstanceOf(ImportException.class);
    }

    @Test
    void unicodeTitlesAndRequestIdsAreBounded() {
        assertThat(manifest(FILE.replace("\"sizeBytes\"", "\"title\":\" \\u00a0 \",\"sizeBytes\""))
                .items().getFirst().title()).isEqualTo("x.txt");
        assertThat(manifest(FILE.replace("\"sizeBytes\"", "\"title\":\"" + "😀".repeat(500) + "\",\"sizeBytes\""))
                .items().getFirst().title()).hasSize(1000);
        for (String title : List.of("\\uD800", "\\uDC00", "a\\u0000b", "😀".repeat(501))) {
            assertThatThrownBy(() -> manifest(FILE.replace("\"sizeBytes\"", "\"title\":\"" + title + "\",\"sizeBytes\"")))
                    .isInstanceOf(ImportException.class);
        }
        assertThat(ImportInput.requestId(" 请求 ")).isEqualTo("请求");
        assertThat(ImportInput.requestId("😀".repeat(256))).hasSize(512);
        for (String id : List.of("", "a\nb", "x".repeat(257), "\uD800")) {
            assertThatThrownBy(() -> ImportInput.requestId(id)).isInstanceOf(ImportException.class);
        }
    }

    @Test
    void rejectsPathsAndAcceptsDistinctItemsWithIdenticalContents() {
        for (String name : List.of("../a.txt", "C:\\a.txt", ".", "..", "a\nb")) {
            assertThatThrownBy(() -> ImportInput.fileName(name)).isInstanceOf(ImportException.class);
        }
        assertThat(manifest(FILE + "," + FILE.replace("\"a\"", "\"b\"")).items()).hasSize(2);
    }

    @Test
    void checksByteLimitsItemLimitsAndCheckedLongTotals() {
        assertThatThrownBy(() -> ImportInput.body(new ByteArrayInputStream(new byte[11]), 10))
                .hasMessage("IMPORT_PAYLOAD_TOO_LARGE");
        byte[] malformed = {(byte) 0xc0, (byte) 0xaf};
        assertThatThrownBy(() -> ImportInput.body(new ByteArrayInputStream(malformed), 10)).hasMessage("IMPORT_INVALID_INPUT");
        ObjectNode two = body("{\"spaceId\":1,\"securityLevel\":0,\"items\":[" + FILE + "," + FILE.replace("\"a\"", "\"b\"") + "]}");
        assertThatThrownBy(() -> ImportInput.manifest(two, 1, 100, 100)).isInstanceOf(ImportException.class);
        assertThatThrownBy(() -> ImportInput.manifest(two, 2, 9, 100)).isInstanceOf(ImportException.class);
        assertThat(ImportInput.manifest(two, 2, 10, 5).totalBytes()).isEqualTo(10);
        ((ObjectNode) two.get("items").get(0)).put("sizeBytes", Long.MAX_VALUE);
        assertThatThrownBy(() -> ImportInput.manifest(two, 2, Long.MAX_VALUE, Long.MAX_VALUE)).hasMessage("IMPORT_INVALID_INPUT");
    }

    @Test
    void derivesBatchStateFromExclusiveCounts() {
        assertThat(new Counts(1, 0, 2, 1).state()).isEqualTo(BatchState.OPEN);
        assertThat(new Counts(0, 1, 2, 0).state()).isEqualTo(BatchState.OPEN);
        assertThat(new Counts(0, 0, 2, 0).state()).isEqualTo(BatchState.COMPLETED);
        assertThat(new Counts(0, 0, 2, 1).state()).isEqualTo(BatchState.PARTIAL_FAILED);
        assertThat(new Counts(0, 0, 0, 1).state()).isEqualTo(BatchState.FAILED);
        assertThatThrownBy(() -> new Counts(-1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private Manifest manifest(String items) {
        return ImportInput.manifest(body("{\"spaceId\":9223372036854775807,\"securityLevel\":0,\"items\":[" + items + "]}"),
                100, 100000, 100000);
    }

    private ObjectNode body(String json) {
        return ImportInput.body(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), ImportInput.MANIFEST_BYTES);
    }
}
