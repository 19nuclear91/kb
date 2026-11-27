package com.zhishiku.kb.app.config;

import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeWork;
import com.zhishiku.kb.app.service.impl.subscription.KnowledgeDerivationService;
import com.zhishiku.kb.app.service.impl.subscription.KnowledgeDerivationTransaction;
import com.zhishiku.kb.app.service.impl.subscription.KnowledgeMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.AnnotationMetadata;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 直接运行Spring条件和调度组件，未创建应用上下文、调度线程或HTTP服务。 */
class KnowledgeSubscriptionSchedulingTest {
    @Test
    void springRegistrationRequiresBothSwitchesAndDefaultsToEnabled() throws Exception {
        Class<?> type = org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class.getAnnotation(Conditional.class).value()[0];
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Condition condition = (Condition) constructor.newInstance();
        for (boolean total : new boolean[]{true, false}) {
            for (boolean knowledge : new boolean[]{true, false}) {
                var context = mock(ConditionContext.class);
                var environment = new StandardEnvironment();
                environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                        "kb.notification.derivation-enabled", total, "kb.knowledge-subscriptions.derivation-enabled", knowledge)));
                when(context.getEnvironment()).thenReturn(environment);
                assertThat(condition.matches(context, AnnotationMetadata.introspect(KnowledgeSubscriptionScheduler.class))).isEqualTo(total && knowledge);
            }
        }
        var context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(new StandardEnvironment());
        assertThat(condition.matches(context, AnnotationMetadata.introspect(KnowledgeSubscriptionScheduler.class))).isTrue();
    }

    @Test
    void failedDiscoveryAndMatchingStillAllowIndependentWeekAndDeliveryBudgets() {
        var transactions = mock(KnowledgeDerivationTransaction.class);
        var metrics = mock(KnowledgeMetrics.class);
        var properties = new KnowledgeSubscriptionProperties();
        var claim = new KnowledgeWork.Claim(UUID.randomUUID(), null, 2, 0, 3, true);
        doThrow(new IllegalStateException()).when(transactions).discover();
        when(transactions.claim(eq("match"), anyString())).thenReturn(List.of(claim));
        when(transactions.match(eq(claim), anyString())).thenThrow(new IllegalStateException());
        when(transactions.claim(eq("weekly"), anyString())).thenReturn(List.of());
        when(transactions.claim(eq("delivery"), anyString())).thenReturn(List.of());
        new KnowledgeDerivationService(transactions, properties, metrics).runOnce();
        verify(transactions).claim(eq("weekly"), anyString());
        verify(transactions).claim(eq("delivery"), anyString());
        verify(transactions).release(eq("match"), eq(claim), anyString(), eq(240), eq(true));
        verify(metrics).result("match", "takeover");
        verify(metrics).result("match", "failure");
    }
}
