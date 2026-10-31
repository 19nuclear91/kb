package com.zhishiku.kb.app.search.execution;

import com.zhishiku.kb.app.search.config.SearchExecutionProperties;
import com.zhishiku.kb.app.search.model.SearchFailure;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 本实例的原子双重准入；票据由实际执行生命周期释放。 */
@Component
public class SearchAdmission {
    private final SearchExecutionProperties properties;
    private final Map<Long, Integer> users = new HashMap<>();
    private int inflight;

    public SearchAdmission(SearchExecutionProperties properties) {
        this.properties = properties;
    }

    public synchronized Ticket acquire(long userId) {
        if (userId <= 0) {
            throw new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
        }
        int count = users.getOrDefault(userId, 0);
        if (count >= properties.maxConcurrentPerUser()) {
            throw new SearchFailure(SearchFailure.Kind.USER_LIMIT, properties.retryAfterSeconds());
        }
        if (inflight >= properties.maxConcurrent()) {
            throw new SearchFailure(SearchFailure.Kind.CAPACITY, properties.retryAfterSeconds());
        }
        users.put(userId, count + 1);
        inflight++;
        return new Ticket(userId);
    }

    public synchronized int inflight() {
        return inflight;
    }

    public synchronized int trackedUsers() {
        return users.size();
    }

    private synchronized void release(long userId) {
        int count = users.get(userId);
        if (count == 1) {
            users.remove(userId);
        } else {
            users.put(userId, count - 1);
        }
        inflight--;
    }

    public final class Ticket implements AutoCloseable {
        private final long userId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Ticket(long userId) {
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
