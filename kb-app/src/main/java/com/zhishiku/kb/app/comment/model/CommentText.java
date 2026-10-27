package com.zhishiku.kb.app.comment.model;

import com.zhishiku.kb.app.query.QueryException;

/**
 * 评论纯文本规则：只去首尾空白，不做 Unicode 兼容折叠、换行改写或静默截断；
 * 按 code point 计数，补充平面字符计 1，保留内部换行。
 */
public final class CommentText {
    public static final int MAX_CODE_POINTS = 4000;

    private CommentText() {
    }

    public static String normalize(String value) {
        if (value == null) {
            throw QueryException.invalid();
        }
        String text = value.strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > MAX_CODE_POINTS) {
            throw QueryException.invalid();
        }
        // 成对代理已合成为补充平面 code point；剩余代理值即非法孤立代理。
        if (text.codePoints().anyMatch(code -> code == 0
                || (code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE))) {
            throw QueryException.invalid();
        }
        return text;
    }
}
