package com.zhishiku.kb.app.knowledgegap.model;

import com.zhishiku.kb.common.api.ApiException;

import java.util.UUID;

/** 纯输入校验；错误及 toString 不回显共享文本。JSON 严格解析由入口负责。 */
public final class KnowledgeGapInputs {
    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final int MAX_OPEN_GAPS = 100;
    public static final String CONSENT_VERSION = "GAP_SHARE_V1";

    private KnowledgeGapInputs() {
    }

    public static ApiException invalid() {
        return ApiException.badRequest("知识问题单输入无效");
    }

    public static String text(String value, int maximum) {
        unicode(value);
        String normalized = value.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > maximum) {
            throw invalid();
        }
        return normalized;
    }

    public static String requestId(String value) {
        unicode(value);
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > 128 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return value;
    }

    public static long positiveId(long value) {
        if (value <= 0) {
            throw invalid();
        }
        return value;
    }

    public static long version(long value) {
        if (value < 0) {
            throw invalid();
        }
        return value;
    }

    public static UUID identity(UUID value) {
        if (value == null) {
            throw invalid();
        }
        return value;
    }

    private static void unicode(String value) {
        if (value == null) {
            throw invalid();
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\0' || Character.isLowSurrogate(current)) {
                throw invalid();
            }
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalid();
                }
                index++;
            }
        }
    }

    public record Creation(KnowledgeGapCategory category, String description, KnowledgeGapSource source,
                           boolean shareAcknowledged) {
        public Creation {
            if (category == null || source == null || !shareAcknowledged) {
                throw invalid();
            }
            description = text(description, 4000);
        }

        @Override
        public String toString() {
            return "Creation[redacted]";
        }
    }

    public record Assignment(long expectedVersion, long assigneeUserId) {
        public Assignment {
            expectedVersion = version(expectedVersion);
            assigneeUserId = positiveId(assigneeUserId);
        }
    }

    /** 补充说明或原因；动作由固定端点决定，不由客户端指定。 */
    public record Message(long expectedVersion, String text) {
        public Message {
            expectedVersion = version(expectedVersion);
            text = KnowledgeGapInputs.text(text, 2000);
        }

        @Override
        public String toString() {
            return "Message[redacted]";
        }
    }

    public record Solution(long expectedVersion, String text, Long documentId, Long versionId) {
        public Solution {
            expectedVersion = version(expectedVersion);
            text = KnowledgeGapInputs.text(text, 4000);
            if ((documentId == null) != (versionId == null)) {
                throw invalid();
            }
            if (documentId != null) {
                positiveId(documentId);
                positiveId(versionId);
            }
        }

        @Override
        public String toString() {
            return "Solution[redacted]";
        }
    }

    public record Confirmation(long expectedVersion, UUID solutionId) {
        public Confirmation {
            expectedVersion = version(expectedVersion);
            solutionId = identity(solutionId);
        }
    }

    public record Rejection(long expectedVersion, UUID solutionId, String reason) {
        public Rejection {
            expectedVersion = version(expectedVersion);
            solutionId = identity(solutionId);
            reason = text(reason, 2000);
        }

        @Override
        public String toString() {
            return "Rejection[redacted]";
        }
    }

    /** 只读候选查询；名称仍为敏感输入，只经请求体传输。 */
    public record Search(String nameQuery, int limit, String cursor) {
        public Search {
            if (nameQuery != null) {
                unicode(nameQuery);
                nameQuery = nameQuery.strip();
                if (nameQuery.codePointCount(0, nameQuery.length()) > 128) {
                    throw invalid();
                }
                if (nameQuery.isEmpty()) {
                    nameQuery = null;
                }
            }
            if (limit < 1 || limit > 100 || (cursor != null && (cursor.isEmpty() || cursor.length() > 2731))) {
                throw invalid();
            }
            if (cursor != null && !cursor.matches("[A-Za-z0-9_-]+")) {
                throw invalid();
            }
        }

        @Override
        public String toString() {
            return "Search[redacted]";
        }
    }
}
