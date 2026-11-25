package com.zhishiku.kb.app.auth.service.diagnostics;

import com.zhishiku.kb.app.config.DiagnosticLimits;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.api.R;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** 鉴权、事实、前后比较及完整编码均在独立的一致只读事务内完成。 */
@Component
public class DiagnosticDatabase {
    private final PlatformTransactionManager manager;
    private final DiagnosticLimits limits;
    private final ObjectMapper json;

    public DiagnosticDatabase(PlatformTransactionManager manager, DiagnosticLimits limits, ObjectMapper json) {
        this.manager = manager;
        this.limits = limits;
        this.json = json;
    }

    public <T> R<T> response(Supplier<T> operation) {
        try (var budget = new DiagnosticBudget(limits.budgetMillis(), System::nanoTime)) {
            var transaction = new TransactionTemplate(manager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.setReadOnly(true);
            transaction.setTimeout(Math.max(1, (limits.budgetMillis() + 999) / 1000));
            R<T> result = transaction.execute(status -> {
                budget.remainingMillis();
                T value = operation.get();
                budget.remainingMillis();
                try {
                    R<T> response = R.ok(value);
                    byte[] encoded = json.writeValueAsBytes(response);
                    if (encoded.length > limits.responseBytes()) {
                        throw new DiagnosticException(DiagnosticException.Reason.DIAGNOSTIC_LIMIT_EXCEEDED);
                    }
                    budget.remainingMillis();
                    return response;
                } catch (JsonProcessingException failure) {
                    throw new DiagnosticException(DiagnosticException.Reason.DIAGNOSTIC_UNAVAILABLE);
                }
            });
            budget.remainingMillis();
            return result;
        } catch (DiagnosticException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DiagnosticException(DiagnosticException.Reason.DIAGNOSTIC_UNAVAILABLE);
        }
    }
}
