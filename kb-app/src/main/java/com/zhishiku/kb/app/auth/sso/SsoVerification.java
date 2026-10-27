package com.zhishiku.kb.app.auth.sso;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 外层预算覆盖缓存等待及全部平台调用；线程与排队均有上限。
 */
public final class SsoVerification {
    private static final ExecutorService WORK = new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
        Thread thread = new Thread(runnable, "sso-verification");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService executor;

    public SsoVerification() {
        this(WORK);
    }

    SsoVerification(ExecutorService executor) {
        this.executor = executor;
    }

    public VerifiedExternalIdentity verify(ExternalLoginProvider provider, Map<String, String> params,
                                           String verifier) {
        Future<VerifiedExternalIdentity> work = null;
        try {
            work = executor.submit(() -> provider.verify(params, verifier));
            return work.get(30, TimeUnit.SECONDS);
        } catch (Exception failed) {
            if (work != null) work.cancel(true);
            if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
            if (failed instanceof ExecutionException execution && execution.getCause() instanceof SsoException safe)
                throw safe;
            throw SsoException.unavailable();
        }
    }
}
