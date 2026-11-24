package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.search.term.mapper.TermMapper;
import org.springframework.stereotype.Service;

/** 目标及本人回执的授权查询基础；不向普通搜索开放管理事实。 */
@Service
public class TermLookup {
    private final TermDatabase database;
    private final TermAccess access;
    private final TermMapper mapper;

    public TermLookup(TermDatabase database, TermAccess access, TermMapper mapper) {
        this.database = database;
        this.access = access;
        this.mapper = mapper;
    }

    public TermRows.Term term(long id) {
        return database.read(() -> {
            access.requireRead();
            TermRows.Term row = mapper.term(id);
            if (row == null) {
                throw new TermException(TermException.Reason.NOT_FOUND);
            }
            return row;
        });
    }

    public TermRows.Correction correction(long id) {
        return database.read(() -> {
            access.requireRead();
            TermRows.Correction row = mapper.correction(id);
            if (row == null) {
                throw new TermException(TermException.Reason.NOT_FOUND);
            }
            return row;
        });
    }

    public TermRows.Command ownCommand(String requestId) {
        return database.read(() -> {
            long actor = access.requireWrite();
            TermRows.Command row = mapper.command(requestId);
            if (row == null || row.actorUserId() != actor) {
                throw new TermException(TermException.Reason.NOT_FOUND);
            }
            return row;
        });
    }
}
