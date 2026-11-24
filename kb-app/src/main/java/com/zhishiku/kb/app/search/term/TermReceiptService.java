package com.zhishiku.kb.app.search.term;

import org.springframework.stereotype.Service;

/** 主动核对本人首次回执，不读取当前实体，也不自动重新发起维护。 */
@Service
public class TermReceiptService {
    private final TermLookup lookup;
    private final TermCommands commands;

    public TermReceiptService(TermLookup lookup, TermCommands commands) {
        this.lookup = lookup;
        this.commands = commands;
    }

    public TermReceipt query(String requestId) {
        TermRows.Command row = lookup.ownCommand(TermInput.requestId(requestId));
        return commands.receipt(row);
    }
}
