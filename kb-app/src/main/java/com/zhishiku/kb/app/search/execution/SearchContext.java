package com.zhishiku.kb.app.search.execution;

/** 搜索线程内的数据库/传输预算，不包含主体句柄或 Servlet 上下文。 */
public final class SearchContext {
    private static final ThreadLocal<SearchContext> CURRENT = new ThreadLocal<>();
    final SearchExecutor.Work<?> work;
    private final SearchBudget.Window window;

    SearchContext(SearchExecutor.Work<?> work, SearchBudget.Window window) {
        this.work = work;
        this.window = window;
    }

    public static SearchContext current() {
        return CURRENT.get();
    }

    public SearchBudget.Window window() {
        return window;
    }

    public void check() {
        work.check();
        window.check();
    }

    public Scope onCancel(Runnable action) {
        return work.onCancel(action);
    }

    public Scope limitTo(long nanos) {
        return work.limitTo(nanos);
    }

    static Scope install(SearchExecutor.Work<?> work, SearchBudget.Window window) {
        SearchContext previous = CURRENT.get();
        CURRENT.set(new SearchContext(work, window));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** 可关闭且不抛受检异常的作用域。 */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
