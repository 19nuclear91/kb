package com.zhishiku.kb.app.auth.lifecycle;

import com.dingtalk.open.app.api.GenericEventListener;
import com.dingtalk.open.app.api.protocol.EventCommandExecutor;
import com.dingtalk.open.app.stream.network.api.Context;
import com.dingtalk.open.app.stream.protocol.CommandType;
import com.dingtalk.open.app.stream.protocol.DefaultProtocolRequestFacade;
import com.dingtalk.open.app.stream.protocol.ProtocolRequest;
import com.dingtalk.open.app.stream.protocol.ProtocolRequestFacade;
import com.dingtalk.open.app.stream.protocol.ProtocolResponse;
import com.dingtalk.open.app.stream.protocol.event.EventAckStatus;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** SDK 只负责事件协议转换；任何错误均只返回固定 LATER，不交给 SDK 记录原文。 */
@Component
public class DingTalkStreamProtocol {
    private final ObjectMapper json;
    private final int maximum;
    private final EventCommandExecutor events;
    private LifecycleObservations observations;

    @Autowired
    public void setObservations(LifecycleObservations observations) {
        this.observations = observations;
    }

    void rejected(DingTalkInputRejected.Reason reason) {
        if (observations != null) {
            observations.rejected(reason);
        }
    }

    public record Reply(String text, boolean disconnect) {
        @Override
        public String toString() {
            return "StreamReply[disconnect=" + disconnect + "]";
        }
    }

    public DingTalkStreamProtocol(DingTalkLifecycleSettings settings, DingTalkEventParser parser,
                                  LifecycleEventConsumer consumer) {
        maximum = settings.maxMessageBytes();
        json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                .maxStringLength(maximum).maxNumberLength(32).build());
        GenericEventListener listener = event -> {
            try {
                consumer.receive(parser.parse(event));
                return EventAckStatus.SUCCESS;
            } catch (DingTalkInputRejected invalid) {
                rejected(invalid.reason());
                return EventAckStatus.LATER;
            } catch (RuntimeException failure) {
                return EventAckStatus.LATER;
            }
        };
        events = new EventCommandExecutor(listener);
    }

    public Reply handle(String text) {
        if (text == null || text.length() > maximum || text.getBytes(StandardCharsets.UTF_8).length > maximum) {
            rejected(text == null ? DingTalkInputRejected.Reason.MALFORMED_MESSAGE : DingTalkInputRejected.Reason.MESSAGE_LIMIT);
            return new Reply(null, true);
        }
        try {
            ProtocolRequest request = json.readValue(text, ProtocolRequest.class);
            var facade = new DefaultProtocolRequestFacade(request);
            if (request.getType() == CommandType.SYSTEM && "disconnect".equals(facade.getTopic())) {
                return new Reply(null, true);
            }
            if ((request.getType() != CommandType.SYSTEM && !"1.0".equals(request.getSpecVersion())) || facade.getMessageId() == null
                    || facade.getMessageId().length() > 256 || facade.getMessageId().isBlank()
                    || !"application/json".equals(facade.getContentType()) || request.getData() == null) {
                rejected(DingTalkInputRejected.Reason.MALFORMED_MESSAGE);
                return new Reply(null, true);
            }
            // 在进入 SDK 的 JSON 转换前拒绝重复字段和过深载荷。
            var data = json.readTree(request.getData());
            if (data == null || !data.isObject()) {
                rejected(DingTalkInputRejected.Reason.MALFORMED_MESSAGE);
                return later(facade);
            }
            if (request.getType() == CommandType.SYSTEM && "ping".equals(facade.getTopic())) {
                return response(facade, data);
            }
            if (request.getType() != CommandType.EVENT) {
                rejected(DingTalkInputRejected.Reason.UNSUPPORTED_COMMAND);
                return later(facade);
            }
            var reply = new AtomicReference<Reply>();
            Context context = new Context() {
                @Override
                public void replay(Object payload) {
                    reply.set(response(facade, payload));
                }

                @Override
                public void exception(Throwable failure) {
                    rejected(DingTalkInputRejected.Reason.MALFORMED_MESSAGE);
                    reply.set(later(facade));
                }

                @Override
                public String connectionId() {
                    return "lifecycle";
                }

                @Override
                public ProtocolRequestFacade getRequest() {
                    return facade;
                }
            };
            try {
                events.execute(context);
            } catch (RuntimeException malformed) {
                rejected(DingTalkInputRejected.Reason.MALFORMED_MESSAGE);
                return later(facade);
            }
            return reply.get() == null ? later(facade) : reply.get();
        } catch (Exception malformed) {
            rejected(DingTalkInputRejected.Reason.MALFORMED_MESSAGE);
            return new Reply(null, true);
        }
    }

    private Reply later(ProtocolRequestFacade request) {
        return response(request, Map.of("status", "LATER"));
    }

    private Reply response(ProtocolRequestFacade request, Object payload) {
        try {
            var response = ProtocolResponse.new200Response(request);
            response.setData(json.writeValueAsString(payload));
            return new Reply(json.writeValueAsString(response), false);
        } catch (Exception failure) {
            return new Reply(null, true);
        }
    }
}
