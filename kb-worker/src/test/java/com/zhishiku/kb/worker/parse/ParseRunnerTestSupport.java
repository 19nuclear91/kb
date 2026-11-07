package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.embedding.execution.WorkerExecution;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecutions;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class ParseRunnerTestSupport {
    private ParseRunnerTestSupport() {
    }

    static WorkerExecutions.Ticket submit(ParseTaskRunner runner) {
        return submit(runner::poll);
    }

    static void run(ParseTaskRunner runner) throws Exception {
        submit(runner).exited().get(5, TimeUnit.SECONDS);
    }

    static void inspect(WorkerExecutions lanes, Consumer<WorkerExecution> callback) throws Exception {
        submit(() -> lanes.submit(WorkerExecution.Purpose.PARSE, callback)).exited().get(5, TimeUnit.SECONDS);
    }

    private static WorkerExecutions.Ticket submit(Supplier<WorkerExecutions.Ticket> action) {
        long start = System.nanoTime();
        do {
            var ticket = action.get();
            if (ticket != null) {
                return ticket;
            }
            Thread.yield();
        } while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        throw new AssertionError("execution handoff timed out");
    }
}
