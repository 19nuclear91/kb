package com.zhishiku.kb.app.document.preview;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.common.api.R;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OfficePreviewController {
    private final OfficePreviewSessions sessions;

    public OfficePreviewController(OfficePreviewSessions sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/api/documents/{documentId}/versions/{versionId}/office-preview/sessions")
    public ResponseEntity<R<OfficePreviewSessions.Launch>> create(@PathVariable long documentId, @PathVariable long versionId) {
        return reply(sessions.create(StpUtil.getTokenValue(), documentId, versionId));
    }

    @PostMapping("/api/office-preview/sessions/{sessionId}/heartbeat")
    public ResponseEntity<R<OfficePreviewSessions.Lease>> heartbeat(@PathVariable String sessionId) {
        return reply(sessions.heartbeat(sessionId, StpUtil.getTokenValue()));
    }

    @DeleteMapping("/api/office-preview/sessions/{sessionId}")
    public ResponseEntity<R<Void>> close(@PathVariable String sessionId) {
        sessions.close(sessionId, StpUtil.getTokenValue());
        return reply(null);
    }

    private static <T> ResponseEntity<R<T>> reply(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .body(R.ok(body));
    }
}
