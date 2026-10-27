package com.zhishiku.kb.worker.parse;

import java.io.InputStream;

/**
 * 未接入第三方服务时的明确不可用状态，不读取或解析原件。
 */
final class UnavailableDocumentParser implements DocumentParser {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String parse(InputStream input, String fileName, String mimeType,
                        int maxExtractedChars) {
        throw new IllegalStateException("第三方解析服务未配置");
    }
}
