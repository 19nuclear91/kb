package com.zhishiku.kb.worker.embedding.model;

public final class RebuildContentChangedException extends RuntimeException {
    public RebuildContentChangedException() {
        super("embedding rebuild content changed");
    }
}
