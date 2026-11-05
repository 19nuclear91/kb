package com.zhishiku.notifier;

final class Rejection extends RuntimeException {
    enum Reason {
        unauthorized, invalid, too_large, media_type, route, method, concurrency, rate,
        remote_http, remote_business, remote_json, timeout, network, cancelled, internal
    }

    private final int status;
    private final Reason reason;

    Rejection(int status, Reason reason) {
        super(reason.name(), null, false, false);
        this.status = status;
        this.reason = reason;
    }

    int status() {
        return status;
    }

    Reason reason() {
        return reason;
    }

    static Rejection invalid() {
        return new Rejection(400, Reason.invalid);
    }
}
