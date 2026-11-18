package com.zhishiku.kb.ai.suggestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.ai.chat.AiProperties;
import com.zhishiku.kb.ai.chat.SuggestionQuota;
import com.zhishiku.kb.ai.model.ChatModelFailure;
import com.zhishiku.kb.ai.model.MetadataSuggestionModel;
import com.zhishiku.kb.ai.model.ModelEvent;
import com.zhishiku.kb.common.suggestion.SuggestionData;
import com.zhishiku.kb.common.suggestion.SuggestionHttp;
import com.zhishiku.kb.common.suggestion.SuggestionProtocol;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class SuggestionInference {
    private final ObjectProvider<MetadataSuggestionModel> models;
    private final SuggestionQuota quota;
    private final SuggestionHttp app;
    private final ObjectMapper json;
    private final long pollMillis;
    private final ConcurrentMap<SuggestionProtocol.Key, Sinks.Empty<Void>> active = new ConcurrentHashMap<>();

    @Autowired
    public SuggestionInference(ObjectProvider<MetadataSuggestionModel> models, SuggestionQuota quota,
                              AiProperties properties, ObjectMapper json, @Value("${kb.metadata-suggestions.secret:}") String secret,
                              @Value("${kb.metadata-suggestions.enabled:false}") boolean enabled) {
        this(models, quota, json, new SuggestionHttp(properties.appBaseUrl(), "kb-ai", secret),
                Math.min(2000, properties.quotaRenewalIntervalMs()));
        if (enabled) {
            app.validate();
        }
    }

    SuggestionInference(ObjectProvider<MetadataSuggestionModel> models, SuggestionQuota quota, ObjectMapper json,
                        SuggestionHttp app, long pollMillis) {
        this.models = models;
        this.quota = quota;
        this.app = app;
        this.json = json;
        this.pollMillis = pollMillis;
    }

    public SuggestionProtocol.Outcome run(SuggestionProtocol.Run command, String token) {
        var model = models.getIfAvailable();
        if (model == null) {
            return SuggestionProtocol.Outcome.failure("MODEL_UNAVAILABLE");
        }
        var key = command.key();
        var output = new Output();
        var stopped = Sinks.<Void>empty();
        if (active.putIfAbsent(key, stopped) != null) {
            return SuggestionProtocol.Outcome.failure("UNAVAILABLE");
        }
        boolean sending = false;
        try {
            var plan = model.prepare(command.material());
            var action = new SuggestionProtocol.Action(key, plan.reservation());
            if (!allowed("reserve", action, token)) {
                return SuggestionProtocol.Outcome.failure("UNAVAILABLE");
            }
            if (!quota.reserve(key, plan.reservation())) {
                return SuggestionProtocol.Outcome.failure("QUOTA_REJECTED");
            }
            if (!allowed("reserved", action, token) || !quota.renew(key) || !allowed("permit", action, token)) {
                return SuggestionProtocol.Outcome.failure("UNAVAILABLE");
            }
            Mono<Void> stop = Mono.fromCallable(() -> allowed("active", action, token) && quota.renew(key))
                    .subscribeOn(Schedulers.boundedElastic()).delaySubscription(Duration.ofMillis(pollMillis))
                    .repeat().filter(active -> !active).next()
                    .flatMap(ignored -> Mono.error(new IllegalStateException("执行已终止")));
            long remaining = key.deadlineMillis() - System.currentTimeMillis();
            if (remaining <= 0) {
                return SuggestionProtocol.Outcome.failure("TIMEOUT");
            }
            sending = true;
            var received = Flux.defer(() -> model.send(plan))
                    .onErrorMap(error -> !(error instanceof ChatModelFailure), error -> new IllegalArgumentException("MODEL_UNAVAILABLE"))
                    .doOnNext(output::add).then();
            Mono<Void> notification = stopped.asMono().then(Mono.error(new IllegalArgumentException("CANCELLED")));
            Mono.firstWithSignal(notification, received, stop).block(Duration.ofMillis(remaining));
            if (output.tooLarge) {
                return output.outcome("OUTPUT_TOO_LARGE", null);
            }
            var result = model.decode(output.text.toString(), command.material());
            try {
                if (json.writeValueAsBytes(result).length > 48000) {
                    return output.outcome("OUTPUT_TOO_LARGE", null);
                }
            } catch (JsonProcessingException failed) {
                throw new IllegalArgumentException("INVALID_MODEL_OUTPUT");
            }
            return output.outcome(null, result);
        } catch (RuntimeException failure) {
            String reason = "UNAVAILABLE";
            if (failure instanceof ChatModelFailure e) {
                reason = switch (e.kind()) {
                    case PAYLOAD_LIMIT -> sending ? "OUTPUT_TOO_LARGE" : "INPUT_TOO_LARGE";
                    case OUTPUT_TRUNCATED -> "OUTPUT_TOO_LARGE";
                    case UNSUPPORTED_OUTPUT -> "INVALID_MODEL_OUTPUT";
                    default -> "MODEL_UNAVAILABLE";
                };
            } else if (failure instanceof IllegalArgumentException && SuggestionProtocol.REASONS.contains(
                    failure.getMessage() == null ? "" : failure.getMessage())) {
                reason = failure.getMessage();
            }
            return output.outcome(reason, null);
        } finally {
            active.remove(key, stopped);
        }
    }

    public boolean stop(SuggestionProtocol.Key key) {
        var signal = active.get(key);
        return signal != null && signal.tryEmitEmpty().isSuccess();
    }

    private boolean allowed(String operation, SuggestionProtocol.Action action, String token) {
        if (System.currentTimeMillis() >= action.key().deadlineMillis()) {
            return false;
        }
        try {
            byte[] response = app.post(operation, json.writeValueAsBytes(action), token, Duration.ofSeconds(4), 128);
            var decoded = json.readTree(response);
            if (decoded.isBoolean()) {
                return decoded.booleanValue();
            }
            String reason = decoded.path("reason").asText("");
            throw new IllegalArgumentException(SuggestionProtocol.REASONS.contains(reason) ? reason : "UNAVAILABLE");
        } catch (IOException failure) {
            throw new IllegalStateException("整理授权未确认");
        }
    }

    private static final class Output {
        private final StringBuilder text = new StringBuilder();
        private volatile ModelEvent.FinalUsage finalUsage;
        private boolean tooLarge;

        SuggestionProtocol.Outcome outcome(String reason, SuggestionData.Result result) {
            var usage = finalUsage;
            return new SuggestionProtocol.Outcome(reason, usage == null ? null : usage.promptTokens(),
                    usage == null ? null : usage.completionTokens(), result);
        }

        void add(ModelEvent event) {
            if (event instanceof ModelEvent.Delta delta) {
                if (text.length() + (long) delta.text().length() > 65536) {
                    tooLarge = true;
                }
                if (!tooLarge) {
                    text.append(delta.text());
                }
            } else if (event instanceof ModelEvent.FinalUsage usage) {
                finalUsage = usage;
            }
        }
    }
}
