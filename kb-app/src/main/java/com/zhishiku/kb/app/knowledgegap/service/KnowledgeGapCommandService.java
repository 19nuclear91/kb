package com.zhishiku.kb.app.knowledgegap.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeMap;

/** 命令绑定必须早于资源锁，并与业务事实共用调用方事务；回执不扩展当前访问资格。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class KnowledgeGapCommandService {
    private static final ObjectMapper JSON = JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final KnowledgeGapMapper mapper;
    private final KnowledgeGapAccess access;

    public KnowledgeGapCommandService(KnowledgeGapMapper mapper, KnowledgeGapAccess access) {
        this.mapper = mapper;
        this.access = access;
    }

    /** 新命令返回 null；冲突后用独立语句读取，不捕获已中止事务继续查询。 */
    public KnowledgeGapRows.Receipt claim(PermissionService.PrincipalHandle actor, KnowledgeGapAction action,
                                         Long gapId, Long expectedVersion, Object input, String requestId) {
        return KnowledgeGapFailures.guarded(() -> {
            actor.requireEnabled();
            String request = KnowledgeGapInputs.requestId(requestId);
            if (action == KnowledgeGapAction.CREATE) {
                if (gapId != null || expectedVersion != null) {
                    throw KnowledgeGapInputs.invalid();
                }
            } else {
                if (gapId == null || expectedVersion == null) {
                    throw KnowledgeGapInputs.invalid();
                }
                KnowledgeGapInputs.positiveId(gapId);
                KnowledgeGapInputs.version(expectedVersion);
                access.requireForAction(actor, gapId, action);
            }
            requirePayload(action, input, expectedVersion);
            var binding = new KnowledgeGapRows.Binding(request, actor.userId(), action, gapId,
                    expectedVersion, digest(actor.userId(), action, gapId, expectedVersion, input));
            mapper.configureLockTimeout();
            int inserted = mapper.claim(binding);
            if (inserted == 1) {
                return null;
            }
            if (inserted != 0) {
                throw KnowledgeGapFailures.unavailable();
            }
            var stored = mapper.command(request);
            if (stored == null) {
                throw KnowledgeGapFailures.unavailable();
            }
            if (stored.actorUserId() != binding.actorUserId() || stored.operation() != binding.operation()
                    || !Objects.equals(stored.gapId(), binding.gapId())
                    || !Objects.equals(stored.expectedVersion(), binding.expectedVersion())
                    || !Objects.equals(stored.inputDigest(), binding.inputDigest())) {
                throw ApiException.conflict("知识问题单请求标识已绑定其他操作");
            }
            if (stored.resultGapId() == null || stored.resultGapId() <= 0 || stored.resultVersion() == null
                    || stored.resultVersion() < 0 || stored.resultState() == null || stored.resultEventId() == null) {
                throw KnowledgeGapFailures.unavailable();
            }
            if ((action != KnowledgeGapAction.CREATE && !stored.resultGapId().equals(gapId))
                    || ((action == KnowledgeGapAction.SOLUTION || action == KnowledgeGapAction.CONFIRM
                        || action == KnowledgeGapAction.REJECT_SOLUTION) && stored.resultSolutionId() == null)) {
                throw KnowledgeGapFailures.unavailable();
            }
            var target = access.requireVisible(actor, stored.resultGapId());
            if (action != KnowledgeGapAction.CREATE) {
                access.requireAction(actor, target, action);
            }
            return new KnowledgeGapRows.Receipt(stored.resultGapId(), stored.resultVersion(), stored.resultState(),
                    stored.resultEventId(), stored.resultSolutionId(), true);
        });
    }

    public KnowledgeGapRows.Receipt complete(String requestId, KnowledgeGapRows.Receipt receipt) {
        return KnowledgeGapFailures.guarded(() -> {
            if (receipt == null || receipt.gapId() <= 0 || receipt.resultVersion() < 0 || receipt.state() == null
                    || receipt.eventId() == null || receipt.replayed()) {
                throw KnowledgeGapFailures.unavailable();
            }
            if (mapper.complete(KnowledgeGapInputs.requestId(requestId), receipt) != 1) {
                throw KnowledgeGapFailures.unavailable();
            }
            return receipt;
        });
    }

    private static void requirePayload(KnowledgeGapAction action, Object input, Long expectedVersion) {
        boolean valid = action != null && switch (action) {
            case CREATE -> input instanceof KnowledgeGapInputs.Creation;
            case ASSIGN -> input instanceof KnowledgeGapInputs.Assignment assignment
                    && Objects.equals(expectedVersion, assignment.expectedVersion());
            case NOTE, RETURN, WITHDRAW, CLOSE -> input instanceof KnowledgeGapInputs.Message message
                    && Objects.equals(expectedVersion, message.expectedVersion());
            case SOLUTION -> input instanceof KnowledgeGapInputs.Solution solution
                    && Objects.equals(expectedVersion, solution.expectedVersion());
            case CONFIRM -> input instanceof KnowledgeGapInputs.Confirmation confirmation
                    && Objects.equals(expectedVersion, confirmation.expectedVersion());
            case REJECT_SOLUTION -> input instanceof KnowledgeGapInputs.Rejection rejection
                    && Objects.equals(expectedVersion, rejection.expectedVersion());
        };
        if (!valid) {
            throw KnowledgeGapInputs.invalid();
        }
    }

    private static String digest(long actor, KnowledgeGapAction action, Long gapId, Long expectedVersion, Object input) {
        var canonical = new TreeMap<String, Object>();
        canonical.put("actor", actor);
        canonical.put("action", action);
        canonical.put("gapId", gapId);
        canonical.put("expectedVersion", expectedVersion);
        canonical.put("input", input);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(canonical)));
        } catch (JsonProcessingException | NoSuchAlgorithmException unavailable) {
            throw KnowledgeGapFailures.unavailable();
        }
    }
}
