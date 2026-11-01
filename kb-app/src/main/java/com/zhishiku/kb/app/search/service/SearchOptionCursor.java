package com.zhishiku.kb.app.search.service;

import com.zhishiku.kb.app.search.model.SearchFailure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 仅携带位置和条件指纹，读取时仍须重新授权；树版本在可见性核对后校验。
 * @param context 用途、用户及查询条件指纹
 * @param treeVersion 树版本，标签使用零
 * @param sortOrder 分类排序，标签使用零
 * @param id 最后一个条目的 ID
 */
record SearchOptionCursor(String context, long treeVersion, int sortOrder, long id) {
    static String context(String purpose, long userId, Long spaceId, Long parentId, String name) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{purpose, Long.toString(userId), String.valueOf(spaceId), String.valueOf(parentId), name == null ? "" : name}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("游标摘要不可用");
        }
    }

    String encode() {
        String value = "search-options-v1:" + context + ":" + treeVersion + ":" + sortOrder + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    static SearchOptionCursor decode(String token, String expectedContext) {
        if (token == null) {
            return null;
        }
        if (!token.matches("[A-Za-z0-9_-]{1,4096}")) {
            throw invalid();
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII).split(":", -1);
            if (parts.length != 5 || !parts[0].equals("search-options-v1") || !parts[1].equals(expectedContext)
                    || !parts[2].matches("0|[1-9][0-9]{0,18}") || !parts[3].matches("0|[1-9][0-9]{0,9}")
                    || !parts[4].matches("[1-9][0-9]{0,18}")) {
                throw invalid();
            }
            return new SearchOptionCursor(parts[1], Long.parseLong(parts[2]), Integer.parseInt(parts[3]), Long.parseLong(parts[4]));
        } catch (IllegalArgumentException invalidToken) {
            throw invalid();
        }
    }

    private static SearchFailure invalid() {
        return new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
    }

    @Override
    public String toString() {
        return "SearchOptionCursor";
    }
}
