package com.zhishiku.kb.eval.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.eval.input.EvaluationException;
import com.zhishiku.kb.eval.input.EvaluationException.Code;

/**
 * 白名单 DTO 序列化，JSON 保留未舍入 double 和 null。
 */
public final class JsonReportWriter {
    private final ObjectMapper mapper = new ObjectMapper();

    public String serialize(EvaluationReport report) {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
        printer.indentArraysWith(new DefaultIndenter("  ", "\n"));
        try {
            return mapper.writer(printer).writeValueAsString(report) + "\n";
        } catch (JsonProcessingException e) {
            throw new EvaluationException(Code.OUTPUT_FAILED, "output", 0, "json");
        }
    }
}
