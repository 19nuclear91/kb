package com.zhishiku.kb.ai.auth;

import com.zhishiku.kb.ai.chat.AiProperties;
import com.zhishiku.kb.ai.chat.ChatController;
import com.zhishiku.kb.ai.chat.ChatModelClient;
import com.zhishiku.kb.ai.chat.ChatQuotaAuditService;
import com.zhishiku.kb.ai.chat.ChatService;
import com.zhishiku.kb.ai.chat.RetrievalClient;
import com.zhishiku.kb.ai.conversation.ConversationAppClient;
import com.zhishiku.kb.ai.conversation.ConversationSettlementService;
import com.zhishiku.kb.ai.conversation.MultiTurnChatService;
import com.zhishiku.kb.ai.conversation.MultiTurnModel;
import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import com.zhishiku.kb.ai.observation.AiObservationClient;
import com.zhishiku.kb.ai.observation.AiObservationProperties;
import com.zhishiku.kb.ai.observation.AiObservedChat;
import com.zhishiku.kb.common.security.AuthenticationCheck;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 原业务回归使用进程内有效身份；撤销、过期及调度暂停由独立虚拟时钟用例覆盖。 */
public final class PermitFixtures {
    private PermitFixtures() {
    }

    public static OutputPermitService healthy() {
        var authentication = mock(AuthenticationClient.class);
        when(authentication.check(anyString())).thenReturn(Mono.just(new AuthenticationCheck(1, 1, false)));
        return new OutputPermitService(authentication, Schedulers.parallel(), System::nanoTime);
    }

    public static ChatService chat(RetrievalClient retrieval) {
        return chat(retrieval, null, null);
    }

    public static ChatService chat(RetrievalClient retrieval, ObjectProvider<ChatModelClient> models) {
        return chat(retrieval, models, null);
    }

    public static ChatService chat(RetrievalClient retrieval, ObjectProvider<ChatModelClient> models, AiProperties properties) {
        var service = new ChatService(retrieval, models, properties);
        service.setOutputPermits(healthy());
        return service;
    }

    public static ChatController controller(ChatService service, AiProperties properties) {
        return controller(service, properties, null);
    }

    public static ChatController controller(ChatService service, AiProperties properties, ChatQuotaAuditService quota) {
        var controller = new ChatController(service, properties, quota);
        controller.setOutputPermits(healthy());
        return controller;
    }

    public static AiObservedChat observed(AiObservationClient client, ChatQuotaAuditService quota, ChatService chat,
                                           AiProperties properties, AiObservationProperties observation) {
        var service = new AiObservedChat(client, quota, chat, properties, observation);
        service.setOutputPermits(healthy());
        return service;
    }

    public static MultiTurnChatService multi(ConversationAppClient app, RetrievalClient retrieval, ChatQuotaAuditService quota,
                                              ConversationSettlementService settlement, ConversationProperties properties,
                                              AiProperties ai, ObjectProvider<MultiTurnModel> models) {
        var service = new MultiTurnChatService(app, retrieval, quota, settlement, properties, ai, models);
        service.setOutputPermits(healthy());
        return service;
    }
}
