package com.zhishiku.kb.app.service.impl.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zhishiku.kb.app.domain.dto.subscription.SubscriptionBinding;
import com.zhishiku.kb.app.domain.subscription.SubscriptionCommand;
import com.zhishiku.kb.app.domain.vo.subscription.SubscriptionViews;
import com.zhishiku.kb.app.mapper.subscription.SubscriptionCommandMapper;
import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Set;

/** 本人原意图锁、规范输入绑定及原子回执，不从当前业务状态推断命令结果。 */
@Service
public class SubscriptionCommands {
    private final SubscriptionCommandMapper mapper;
    private final ObjectMapper json;

    public SubscriptionCommands(SubscriptionCommandMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public SubscriptionBinding bind(long actor, String requestId, String operation, Object... fields) {
        if (actor <= 0 || !Set.of("SUBSCRIBE", "CANCEL", "PREFERENCE").contains(operation)) {
            throw SubscriptionFailure.invalid();
        }
        String request = SubscriptionRules.text(requestId, 1, 128);
        try {
            var canonical = json.writeValueAsString(Arrays.asList(1, actor, operation, Arrays.asList(fields)));
            return new SubscriptionBinding(actor, request, operation, SubscriptionRules.digest(canonical));
        } catch (Exception ignored) {
            throw SubscriptionFailure.unavailable();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SubscriptionViews.Receipt begin(SubscriptionBinding binding) {
        mapper.lockRequest("knowledge-command:" + binding.actor() + ":" + binding.requestId());
        var previous = mapper.find(binding.actor(), binding.requestId());
        if (previous != null) {
            if (!binding.operation().equals(previous.operation()) || !binding.digest().equals(previous.inputDigest())) {
                throw SubscriptionFailure.conflict("REQUEST_MISMATCH");
            }
            return read(previous).asReplay();
        }
        SubscriptionRules.changed(mapper.insert(new SubscriptionCommand(binding.actor(), binding.requestId(),
                binding.operation(), binding.digest(), null, null)));
        return null;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SubscriptionViews.Receipt complete(SubscriptionBinding binding, SubscriptionViews.Receipt receipt) {
        try {
            String encoded = json.writeValueAsString(receipt);
            SubscriptionRules.changed(mapper.complete(new SubscriptionCommand(binding.actor(), binding.requestId(),
                    binding.operation(), binding.digest(), encoded, null)));
            return receipt;
        } catch (SubscriptionFailure failure) {
            throw failure;
        } catch (Exception ignored) {
            throw SubscriptionFailure.unavailable();
        }
    }

    public SubscriptionViews.ReceiptLookup lookup(long actor, String requestId) {
        String request = SubscriptionRules.text(requestId, 1, 128);
        var command = mapper.find(actor, request);
        if (command == null || command.receipt() == null) {
            return new SubscriptionViews.ReceiptLookup(request, "UNKNOWN", null);
        }
        return new SubscriptionViews.ReceiptLookup(request, "CONFIRMED", read(command));
    }

    private SubscriptionViews.Receipt read(SubscriptionCommand command) {
        try {
            var receipt = json.readValue(command.receipt(), SubscriptionViews.Receipt.class);
            if (receipt == null || !command.requestId().equals(receipt.requestId())
                    || !command.operation().equals(receipt.operation()) || receipt.effectiveAt() == null) {
                throw SubscriptionFailure.unavailable();
            }
            return receipt;
        } catch (Exception ignored) {
            throw SubscriptionFailure.unavailable();
        }
    }
}
