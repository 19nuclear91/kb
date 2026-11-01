package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.document.metadata.model.DocumentFileTypes;
import com.zhishiku.kb.app.search.model.SearchFilters;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchFileTypeTest {
    @Test
    void publishedFileRulesDistinguishExtensionsAndRequireMatchingMime() {
        assertThat(DocumentFileTypes.classify(1L, "CURRENT.PDF", "APPLICATION/PDF").name()).isEqualTo("PDF");
        assertThat(DocumentFileTypes.classify(1L, "current.md", "text/plain").name()).isEqualTo("MARKDOWN");
        assertThat(DocumentFileTypes.classify(1L, "current.txt", "text/plain").name()).isEqualTo("TXT");
        assertThat(DocumentFileTypes.classify(1L, "candidate.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document").name()).isEqualTo("WORD");
        assertThat(DocumentFileTypes.classify(1L, "mismatch.pdf", "text/plain").name()).isEqualTo("OTHER");
        assertThat(DocumentFileTypes.classify(1L, null, null).name()).isEqualTo("OTHER");
        assertThat(DocumentFileTypes.classify(null, "draft.pdf", "application/pdf").name()).isEqualTo("UNPUBLISHED");
        for (var rule : DocumentFileTypes.RULES) {
            assertThat(SearchFilters.FileType.valueOf(rule.type().name())).isNotNull();
        }
    }
}
