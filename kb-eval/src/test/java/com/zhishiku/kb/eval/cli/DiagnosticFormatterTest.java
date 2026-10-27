package com.zhishiku.kb.eval.cli;

import com.zhishiku.kb.eval.input.EvaluationException;
import com.zhishiku.kb.eval.input.EvaluationException.Code;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticFormatterTest {
    @Test
    void onlyControlledErrorLocationIsPrinted() {
        var error = new EvaluationException(Code.INVALID_FIELD, "run[2].results", 17, "versionId");
        assertThat(DiagnosticFormatter.format(error))
                .isEqualTo("EVAL_ERROR INVALID_FIELD role=run[2].results line=17 field=versionId");
    }

    @Test
    void arbitraryRoleFieldAndCauseTextNeverReachDiagnostic() {
        var error = new EvaluationException(Code.INVALID_JSON, "query_BODY_TOKEN", 2, "password_SECRET");
        error.initCause(new RuntimeException("raw-line QUERY_SENTINEL CONTENT_SENTINEL TOKEN_SENTINEL"));
        String output = DiagnosticFormatter.format(error);
        assertThat(output).isEqualTo("EVAL_ERROR INVALID_JSON role=input line=2 field=input")
                .doesNotContain("query_BODY_TOKEN", "password_SECRET", "raw-line", "QUERY_SENTINEL",
                        "CONTENT_SENTINEL", "TOKEN_SENTINEL");
    }
}
