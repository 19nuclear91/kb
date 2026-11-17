package com.zhishiku.kb.app.document.diff.service;

public final class DiffException extends RuntimeException {
    private final int status;
    private final String reasonCode;

    public DiffException(int status, String reasonCode) {
        super(reasonCode, null, false, false);
        this.status = status;
        this.reasonCode = reasonCode;
    }

    public int status() {
        return status;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
