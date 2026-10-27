package com.zhishiku.kb.worker.parse;

import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTest {

    private final FailureClassifier classifier = new FailureClassifier();

    @Test
    void classifiesPermanentParserAndInputFailures() {
        FailureClassifier.Classification parser = classifier.classify(
                new ParseFailureException("parser failed", new ParsePipeline.ParsedDocument(List.of())));
        FailureClassifier.Classification input = classifier.classify(new IllegalArgumentException("bad input"));

        assertThat(parser.failureClass()).isEqualTo(FailureClassifier.FailureClass.PERMANENT);
        assertThat(parser.code()).isEqualTo("PARSER_ERROR");
        assertThat(input.failureClass()).isEqualTo(FailureClassifier.FailureClass.PERMANENT);
        assertThat(input.code()).isEqualTo("INVALID_INPUT");
    }

    @Test
    void classifiesDependenciesAndUnknownAsRetryable() throws Exception {
        assertThat(classifier.classify(SdkClientException.create("s3 timeout")).code())
                .isEqualTo("OBJECT_STORAGE_UNAVAILABLE");
        assertThat(classifier.classify(new TransientDataAccessResourceException("db down")).retryable())
                .isTrue();
        assertThat(classifier.classify(new TimeoutException("embedding timeout")).code())
                .isEqualTo("DEPENDENCY_TIMEOUT");
        assertThat(classifier.classify(new IllegalStateException("unexpected")).retryable())
                .isTrue();
    }

    @Test
    void treatsLeaseLossAsControlFlowAndRedactsSummary() {
        FailureClassifier.Classification lease = classifier.classify(new LeaseLostException());
        FailureClassifier.Classification redacted = classifier.classify(
                new IllegalStateException("Bearer top-secret token=abc\n" + "x".repeat(3000)));

        assertThat(lease.leaseLost()).isTrue();
        assertThat(lease.failureClass()).isNull();
        assertThat(redacted.summary()).doesNotContain("top-secret", "token=abc")
                .doesNotContain("\n")
                .hasSizeLessThanOrEqualTo(FailureClassifier.MAX_SUMMARY_LENGTH);
    }
}
