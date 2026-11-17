package com.zhishiku.kb.app.document.diff.service;

import com.zhishiku.kb.app.document.diff.DocumentDiffProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class DiffAdmission {
    private final int maximum;
    private int inflight;

    public DiffAdmission(DocumentDiffProperties properties) {
        maximum = properties.maxConcurrent();
    }

    public synchronized Ticket acquire() {
        if (inflight >= maximum) {
            throw new DiffException(429, "DIFF_CAPACITY");
        }
        inflight++;
        return new Ticket();
    }

    public synchronized int inflight() {
        return inflight;
    }

    public final class Ticket implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();

        private Ticket() {
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                synchronized (DiffAdmission.this) {
                    inflight--;
                }
            }
        }
    }
}
