package com.zhishiku.kb.app.document.imports.support;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 游标绑定排序、本人及批次；它只是查询位置，不是授权凭据。 */
public final class ImportCursor {
    private ImportCursor() {
    }

    public static String encode(long owner, long importId, long position) {
        String value = "1:" + owner + ":" + importId + ":" + position;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    public static Long decode(String cursor, long owner, long importId) {
        if (cursor == null) {
            return null;
        }
        if (cursor.isBlank() || cursor.length() > 128 || !cursor.matches("[A-Za-z0-9_-]+")) {
            throw ImportInput.invalid();
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            String[] parts = value.split(":", -1);
            if (parts.length != 4 || !parts[0].equals("1") || ImportInput.id(parts[1]) != owner
                    || ImportInput.revision(parts[2]) != importId) {
                throw ImportInput.invalid();
            }
            long position = ImportInput.id(parts[3]);
            if (importId != 0 && position > 500) {
                throw ImportInput.invalid();
            }
            if (!encode(owner, importId, position).equals(cursor)) {
                throw ImportInput.invalid();
            }
            return position;
        } catch (IllegalArgumentException ignored) {
            throw ImportInput.invalid();
        }
    }

    public static int limit(int limit) {
        if (limit < 1 || limit > 100) {
            throw ImportInput.invalid();
        }
        return limit;
    }
}
