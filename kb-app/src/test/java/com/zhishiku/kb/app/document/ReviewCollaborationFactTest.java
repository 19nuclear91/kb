package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.collaboration.service.CollaborationEventService;
import com.zhishiku.kb.app.collaboration.service.ReviewProtocolCoverage;
import com.zhishiku.kb.app.document.model.ReviewReceipt;
import com.zhishiku.kb.common.review.CollaborationEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewCollaborationFactTest {
    @Test
    void eventRecipientOrReceiptFailureCannotAcknowledgeSuccess() {
        var event = new ReviewCommandFixture();
        when(event.eventMapper.append(any())).thenThrow(new IllegalStateException("event unavailable"));
        assertThatThrownBy(() -> event.service.submit(8, 10, 20, 0, "event-failure"))
                .isInstanceOf(IllegalStateException.class);
        verify(event.receipts, never()).complete(any(), any());
        var recipient = new ReviewCommandFixture();
        when(recipient.eventMapper.recipient(any(), anyLong())).thenReturn(0);
        assertThatThrownBy(() -> recipient.service.submit(8, 10, 20, 0, "recipient-failure"))
                .isInstanceOf(IllegalStateException.class);
        verify(recipient.receipts, never()).complete(any(), any());
        var receipt = new ReviewCommandFixture();
        doThrow(new IllegalStateException("receipt unavailable")).when(receipt.receipts).complete(any(), any());
        assertThatThrownBy(() -> receipt.service.submit(8, 10, 20, 0, "receipt-failure"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(CollaborationEventService.class.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.MANDATORY);
        // 这里只验证失败传播及事务配置，不宣称真实数据库已经回滚。
    }

    @Test
    void duplicateRequestKeepsOneFactAndDoesNotNeedNotificationConsumer() {
        var f = new ReviewCommandFixture();
        var keys = new HashSet<String>();
        when(f.eventMapper.append(any())).thenAnswer(call -> keys.add(call.getArgument(0,
                CollaborationEvent.class).businessKey()) ? 1 : 0);
        var first = f.service.submit(8, 10, 20, 0, "same");
        when(f.receipts.claim(any())).thenReturn("saved");
        when(f.receipts.read("saved", ReviewReceipt.class)).thenReturn(first);
        assertThat(f.service.submit(8, 10, 20, 0, "same")).isEqualTo(first);
        assertThat(keys).hasSize(1);
        verify(f.eventMapper, times(1)).append(any());
        verify(f.eventMapper, times(2)).recipient(any(), anyLong());
        assertThat(CollaborationEventService.class.getDeclaredConstructors()).singleElement().satisfies(constructor ->
                assertThat(constructor.getParameterTypes()).hasSize(1));
    }

    @Test
    void coverageStartIsAppendOnlyAndDoesNotReadHistoricalIndexOrAuditFacts() {
        var f = new ReviewCommandFixture();
        var coverage = new ReviewProtocolCoverage(f.eventMapper);
        coverage.start();
        coverage.start();
        verify(f.eventMapper, times(2)).startCoverage(any());
        verify(f.eventMapper, never()).append(any());
        verify(f.eventMapper, never()).recipient(any(), anyLong());
    }
}
