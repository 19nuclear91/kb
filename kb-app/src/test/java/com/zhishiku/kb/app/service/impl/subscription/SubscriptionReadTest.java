package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.domain.subscription.KnowledgeSubscription;
import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;
import com.zhishiku.kb.app.enums.subscription.SubscriptionState;
import com.zhishiku.kb.app.mapper.subscription.SubscriptionMapper;
import com.zhishiku.kb.app.mapper.subscription.SubscriptionQueryMapper;
import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SubscriptionReadTest {
    @Test
    void inaccessibleTargetKeepsOnlyOwnersCancellationIdentity() {
        var access = mock(SubscriptionAccess.class);
        var mapper = mock(SubscriptionMapper.class);
        var queries = mock(SubscriptionQueryMapper.class);
        var service = new SubscriptionReadServiceImpl(access, mapper, queries, mock(SubscriptionCommands.class));
        var scope = PermissionScope.of(1, (short) 1, List.of(), List.of("1:1"));
        when(access.current(1)).thenReturn(scope);
        var row = new KnowledgeSubscription(UUID.randomUUID(), 1, SubscriptionKind.DOCUMENT, 9007199254740993L,
                SubscriptionState.ACTIVE, UUID.randomUUID(), 1, Instant.now(), Instant.now());
        when(queries.subscriptions(any())).thenReturn(List.of(row));
        var item = service.list(1, null, null, 20, null).items().getFirst();
        assertThat(item.subscriptionId()).isEqualTo(row.id());
        assertThat(item.generationId()).isEqualTo(row.generationId());
        assertThat(item.available()).isFalse();
        assertThat(item.targetId()).isNull();
        assertThat(item.label()).isNull();
        assertThat(service.preference(1).mode()).isEqualTo(SubscriptionMode.WEEKLY);
        assertThat(service.preference(1).version()).isEqualTo("0");
    }

    @Test
    void cursorsAndInputValidationDoNotExtendAuthority() {
        String context = SubscriptionCursor.context(1, "subscriptions", "ACTIVE");
        String cursor = SubscriptionCursor.encode(context, UUID.randomUUID().toString());
        assertThatThrownBy(() -> SubscriptionCursor.decode(cursor, SubscriptionCursor.context(2, "subscriptions", "ACTIVE")))
                .isInstanceOf(SubscriptionFailure.class);
        assertThat(SubscriptionRules.pattern("100%_\\")).isEqualTo("%100\\%\\_\\\\%");
        assertThatThrownBy(() -> SubscriptionRules.number("9223372036854775808", 1)).isInstanceOf(SubscriptionFailure.class);
        assertThatThrownBy(() -> SubscriptionRules.text("\uD800", 0, 128)).isInstanceOf(SubscriptionFailure.class);
        assertThatThrownBy(() -> SubscriptionRules.text("request\n", 1, 128)).isInstanceOf(SubscriptionFailure.class);
        assertThat(SubscriptionRules.text(" request ", 1, 128)).isEqualTo(" request ");
        var queries = mock(SubscriptionQueryMapper.class);
        var service = new SubscriptionReadServiceImpl(mock(SubscriptionAccess.class), mock(SubscriptionMapper.class),
                queries, mock(SubscriptionCommands.class));
        assertThatThrownBy(() -> service.list(1, null, null, 101, null)).isInstanceOf(SubscriptionFailure.class);
        verifyNoInteractions(queries);
    }
}
