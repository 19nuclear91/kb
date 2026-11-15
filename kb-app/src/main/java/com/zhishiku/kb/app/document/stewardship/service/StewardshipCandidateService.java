package com.zhishiku.kb.app.document.stewardship.service;

import com.zhishiku.kb.app.document.applicability.service.BusinessDate;
import com.zhishiku.kb.app.document.applicability.service.ValidityCursor;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.app.document.applicability.support.ValidityInput;
import com.zhishiku.kb.app.document.stewardship.mapper.StewardshipMapper;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipInputs;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipViews;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StewardshipCandidateService {
    private final StewardshipMapper mapper;
    private final StewardshipAccess access;
    private final BusinessDate date;

    public StewardshipCandidateService(StewardshipMapper mapper, StewardshipAccess access, BusinessDate date) {
        this.mapper = mapper;
        this.access = access;
        this.date = date;
    }

    @Transactional(readOnly = true, timeout = 3)
    public StewardshipViews.Candidates query(long actor, long document, StewardshipInputs.Candidates input) {
        if (input.limit() < 1 || input.limit() > 100 || input.nameQuery() == null || input.nameQuery().codePointCount(0, input.nameQuery().length()) > 64) {
            throw ValidityInput.invalid();
        }
        String context = ValidityCursor.context("stewardship-candidates", actor, document, input.nameQuery() + ":" + input.limit());
        var cursor = ValidityCursor.decode(input.cursor(), context);
        if (cursor != null && (cursor.date() != null || cursor.time() != null)) {
            throw ValidityInput.invalid();
        }
        access.manager(actor, document, false);
        var rows = mapper.candidates(document, ValidityInput.literalPattern(input.nameQuery()), cursor == null ? null : cursor.id(), input.limit() + 1);
        if (rows == null || rows.size() > input.limit() + 1) {
            throw new ValidityException(ValidityException.Reason.UNAVAILABLE);
        }
        var page = List.copyOf(rows.subList(0, Math.min(input.limit(), rows.size())));
        String next = rows.size() > input.limit() ? new ValidityCursor(context, page.getLast().userId(), null, null).encode() : null;
        return new StewardshipViews.Candidates(page, next, date.today());
    }
}
