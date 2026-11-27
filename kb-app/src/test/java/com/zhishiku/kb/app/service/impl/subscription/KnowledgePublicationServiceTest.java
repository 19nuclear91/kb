package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.mapper.subscription.KnowledgeOrderMapper;
import com.zhishiku.kb.common.review.PublicationOrder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgePublicationServiceTest {
    @Test
    void preservesDatabaseOrderAndRejectsMissingContext() {
        var mapper = mock(KnowledgeOrderMapper.class);
        var service = new KnowledgePublicationServiceImpl(mapper);
        var order = new PublicationOrder(42, Instant.parse("2026-11-27T08:00:00Z"));
        var event = UUID.randomUUID();
        when(mapper.next()).thenReturn(order);
        assertThat(service.next()).isSameAs(order);
        assertThatThrownBy(() -> service.capture(event, order)).isInstanceOf(IllegalStateException.class);
        when(mapper.capture(event, order.position(), order.businessTime())).thenReturn(1);
        service.capture(event, order);
        verify(mapper).next();
    }

    @Test
    void propagatesStorageFailuresInsteadOfReportingPublicationSuccess() {
        var mapper = mock(KnowledgeOrderMapper.class);
        var service = new KnowledgePublicationServiceImpl(mapper);
        assertThatThrownBy(service::next).isInstanceOf(IllegalStateException.class);
        var failure = new IllegalStateException("storage unavailable");
        when(mapper.next()).thenThrow(failure);
        assertThatThrownBy(service::next).isSameAs(failure);
        assertThatThrownBy(() -> new PublicationOrder(0, Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }
}
