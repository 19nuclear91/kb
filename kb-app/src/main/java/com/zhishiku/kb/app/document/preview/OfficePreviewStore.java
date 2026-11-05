package com.zhishiku.kb.app.document.preview;

public interface OfficePreviewStore {
    void create(OfficePreviewSession session, long now, int maxPerUser, int maxTotal);

    OfficePreviewSession find(String id, long now);

    OfficePreviewSession activate(OfficePreviewSession pending, String launchDigest, String cookieDigest,
                                  long now, long expiresAt);

    OfficePreviewSession heartbeat(OfficePreviewSession active, long now, long expiresAt);

    void close(OfficePreviewSession session);
}
