package com.zhishiku.kb.app.document.imports.service;

import com.zhishiku.kb.app.document.imports.ImportProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 每实例真实同步工作名额；不排队、不按响应是否已经交付释放。 */
@Component
public class ImportCapacity {
    private final int maximum;
    private final int perUser;
    private final Map<Long, Integer> users = new HashMap<>();
    private int active;

    public ImportCapacity(ImportProperties properties) {
        maximum = properties.capacity();
        perUser = properties.userCapacity();
    }

    public synchronized Reservation acquire(long userId) {
        int current = users.getOrDefault(userId, 0);
        if (active >= maximum || current >= perUser) {
            throw new ImportException(ImportException.Reason.CAPACITY);
        }
        active++;
        users.put(userId, current + 1);
        return new Reservation(userId);
    }

    private synchronized void release(long userId) {
        int remaining = users.get(userId) - 1;
        if (remaining == 0) {
            users.remove(userId);
        } else {
            users.put(userId, remaining);
        }
        active--;
    }

    public final class Reservation implements AutoCloseable {
        private final long userId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Reservation(long userId) {
            this.userId = userId;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release(userId);
            }
        }
    }
}
