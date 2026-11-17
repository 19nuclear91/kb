package com.zhishiku.kb.app.document.changenote.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteInput;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteViews;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteReadService;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteWriteService;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteException;
import com.zhishiku.kb.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Set;

@RestController
public class ChangeNoteController {
    private final ChangeNoteReadService reads;
    private final ChangeNoteWriteService writes;

    public ChangeNoteController(ChangeNoteReadService reads, ChangeNoteWriteService writes) {
        this.reads = reads;
        this.writes = writes;
    }

    @GetMapping("/api/documents/{documentId}/versions/{versionId}/change-note")
    public R<ChangeNoteViews.Detail> get(@PathVariable String documentId, @PathVariable String versionId, HttpServletRequest request) {
        query(request, Set.of());
        return R.ok(reads.get(StpUtil.getLoginIdAsLong(), id(documentId), id(versionId)));
    }

    @GetMapping("/api/documents/{documentId}/versions/{versionId}/change-note/history")
    public R<ChangeNoteViews.History> history(@PathVariable String documentId, @PathVariable String versionId, HttpServletRequest request) {
        query(request, Set.of("limit", "cursor"));
        int limit = 20;
        if (request.getParameter("limit") != null) {
            long supplied = id(request.getParameter("limit"));
            if (supplied > 100) {
                throw ChangeNoteInput.invalid();
            }
            limit = (int) supplied;
        }
        return R.ok(reads.history(StpUtil.getLoginIdAsLong(), id(documentId), id(versionId), limit, request.getParameter("cursor")));
    }

    @PutMapping("/api/documents/{documentId}/versions/{versionId}/change-note")
    public R<ChangeNoteViews.Receipt> update(@PathVariable String documentId, @PathVariable String versionId,
                                           @RequestHeader("X-Request-Id") String requestId, HttpServletRequest request) throws IOException {
        query(request, Set.of());
        if (request.getContentLengthLong() > ChangeNoteInput.BODY_LIMIT) {
            throw new ChangeNoteException(ChangeNoteException.Reason.PAYLOAD_TOO_LARGE);
        }
        var input = ChangeNoteInput.parse(request.getInputStream());
        return R.ok(writes.update(StpUtil.getLoginIdAsLong(), id(documentId), id(versionId), input, ChangeNoteInput.requestId(requestId)));
    }

    private static long id(String raw) {
        try {
            if (raw == null || !raw.matches("[1-9][0-9]{0,18}")) {
                throw ChangeNoteInput.invalid();
            }
            return Long.parseLong(raw);
        } catch (NumberFormatException invalid) {
            throw ChangeNoteInput.invalid();
        }
    }

    private static void query(HttpServletRequest request, Set<String> allowed) {
        request.getParameterMap().forEach((key, values) -> {
            if (!allowed.contains(key) || values.length != 1) {
                throw ChangeNoteInput.invalid();
            }
        });
    }
}
