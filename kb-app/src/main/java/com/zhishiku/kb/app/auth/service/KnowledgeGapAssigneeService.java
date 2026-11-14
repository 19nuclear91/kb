package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.KnowledgeGapAssigneeMapper;
import com.zhishiku.kb.app.auth.model.KnowledgeGapAssigneeViews;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Supplier;

/** 调用方先检查具体问题可见性；本服务只处理最小人员池和当次资格，不缓存结果。 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class KnowledgeGapAssigneeService {
    private final KnowledgeGapAssigneeMapper mapper;

    public KnowledgeGapAssigneeService(KnowledgeGapAssigneeMapper mapper) {
        this.mapper = mapper;
    }

    public List<KnowledgeGapAssigneeViews.Candidate> candidates(PermissionService.PrincipalHandle actor,
                                                               String nameQuery, Long afterId, int fetch) {
        return guarded(() -> {
            actor.requireEnabled();
            if (!actor.functionalPermissions().contains(Permission.KNOWLEDGE_GAP_ASSIGN.code())) {
                throw ApiException.forbidden("知识问题单操作被拒绝");
            }
            if (fetch < 1 || fetch > 101 || (afterId != null && afterId <= 0)) {
                throw QueryException.invalid();
            }
            String name = normalizeName(nameQuery);
            var rows = mapper.candidates(QueryParameters.like(name), afterId, fetch);
            if (rows == null || rows.size() > fetch) {
                throw new IllegalStateException("Invalid assignee page");
            }
            long previous = afterId == null ? 0 : afterId;
            for (var row : rows) {
                if (row == null || row.userId() <= previous || row.label() == null) {
                    throw new IllegalStateException("Inconsistent assignee page");
                }
                previous = row.userId();
            }
            return List.copyOf(rows);
        });
    }

    /** 实际分派和接管提示必须重新查询，不能用候选页面作授权快照。 */
    public boolean eligible(long userId) {
        return guarded(() -> {
            if (userId <= 0) {
                throw QueryException.invalid();
            }
            var qualification = mapper.qualification(userId);
            if (qualification != null && qualification.userId() != userId) {
                throw new IllegalStateException("Inconsistent assignee identity");
            }
            return qualification != null && qualification.eligible();
        });
    }

    public void requireEligible(long userId) {
        if (!eligible(userId)) {
            throw ApiException.conflict("处理人候选已失效");
        }
    }

    public static String normalizeName(String value) {
        if (value == null) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\0' || Character.isLowSurrogate(current)) {
                throw QueryException.invalid();
            }
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw QueryException.invalid();
                }
                index++;
            }
        }
        String name = value.strip();
        if (name.codePointCount(0, name.length()) > 128) {
            throw QueryException.invalid();
        }
        return name.isEmpty() ? null : name;
    }

    private static <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (ApiException | QueryException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw ApiException.unavailable("处理人候选暂不可用");
        }
    }
}
