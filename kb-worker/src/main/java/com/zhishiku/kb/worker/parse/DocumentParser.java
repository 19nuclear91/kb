package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.model.ParseIssue;
import com.zhishiku.kb.worker.parse.model.ParsedDocument;

import java.io.InputStream;

@FunctionalInterface
public interface DocumentParser {

    /**
     * 调用第三方服务抽取原件文本。所有格式均由外部服务解析，不提供本地解析回退。
     */
    String parse(InputStream input, String fileName, String mimeType,
                 int maxExtractedChars) throws Exception;

    /**
     * 本地接入配置是否就绪；不得在此执行网络请求或读取原件。
     * 未就绪时暂停领取任务，不消耗重试次数。
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 第三方解析结果的统一中间模型。纯文本服务的返回值可在此包装为结构块；
     * 结构化服务应覆盖此方法，保留标题、表格、页码、来源坐标和解析问题。
     * 本地包装仅转换服务结果，不抽取原件内容。
     */
    default ParsedDocument parseDocument(InputStream input, String fileName, String mimeType,
                                         int maxExtractedChars) throws Exception {
        ParsedDocument result = ParsedDocument.fromPlainText(fileName, mimeType,
                        parse(input, fileName, mimeType, maxExtractedChars))
                .withParser("legacy-text", "1");
        if (result.blocks().isEmpty() && result.issues().isEmpty()) {
            result = result.withIssue(new ParseIssue(ParseIssue.Severity.ERROR,
                    "EMPTY_CONTENT", "解析结果不包含可索引内容", null));
        }
        return result;
    }
}
