package com.zhishiku.kb.worker.parse;

/**
 * 解析结果包含结构化错误时携带原始统一模型，供失败记录保存诊断信息。
 */
public final class ParseFailureException extends IllegalArgumentException {

    private final ParsePipeline.ParsedDocument parsed;

    public ParseFailureException(String message, ParsePipeline.ParsedDocument parsed) {
        super(message);
        this.parsed = parsed;
    }

    public ParsePipeline.ParsedDocument parsed() {
        return parsed;
    }
}
