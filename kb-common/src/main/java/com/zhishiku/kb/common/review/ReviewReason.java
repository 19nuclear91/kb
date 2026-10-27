package com.zhishiku.kb.common.review;

import java.text.Normalizer;

/** 审核原因作为纯文本保存与呈现；统一换行及 Unicode 规范化后计算摘要。 */
public final class ReviewReason {
    private ReviewReason() {
    }

    public static String normalize(String value) {
        if (value == null) throw new ReviewFailure(ReviewError.INVALID_REQUEST);
        String text = Normalizer.normalize(value.replace("\r\n", "\n").replace('\r', '\n').strip(), Normalizer.Form.NFC);
        int size = text.codePointCount(0, text.length());
        if (size < 1 || size > 2000 || text.codePoints().anyMatch(code ->
                (Character.isISOControl(code) && code != '\n' && code != '\t')
                        || (code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE)))
            throw new ReviewFailure(ReviewError.INVALID_REQUEST);
        return text;
    }
}
