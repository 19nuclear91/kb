package com.zhishiku.kb.worker.embedding.model;

public final class RebuildLeaseLostException extends RuntimeException {
    public RebuildLeaseLostException() {
        super("embedding rebuild lease lost");
    }
}
