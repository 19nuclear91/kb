package com.zhishiku.kb.app.document.changenote.service;

public class ChangeNoteException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT(400), UNAUTHORIZED(401), FORBIDDEN(403), PASSWORD_CHANGE_REQUIRED(403), NOT_FOUND(404),
        REVIEW_IN_PROGRESS(409), REASON_REQUIRED(409), NOTE_REMOVAL_NOT_ALLOWED(409), NO_BASE_VERSION(409),
        REQUEST_MISMATCH(409), NOTE_VERSION_CONFLICT(409), NO_CHANGE(409), PAYLOAD_TOO_LARGE(413), UNAVAILABLE(503);

        private final int status;

        Reason(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final Reason reason;

    public ChangeNoteException(Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
