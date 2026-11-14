package com.zhishiku.kb.app.knowledgegap.service;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** 当前参与范围与动作资格分开核验；不保存主体句柄或授权快照。 */
@Component
public class KnowledgeGapAccess {
    private final AdminQueryAccess identities;
    private final KnowledgeGapMapper mapper;

    public KnowledgeGapAccess(AdminQueryAccess identities, KnowledgeGapMapper mapper) {
        this.identities = identities;
        this.mapper = mapper;
    }

    public PermissionService.PrincipalHandle current() {
        return guarded(identities::current);
    }

    public PermissionService.PrincipalHandle requireCurrent(Permission permission) {
        return guarded(() -> {
            var handle = identities.current();
            requirePermission(handle, permission);
            return handle;
        });
    }

    public KnowledgeGapRows.Gap requireVisible(PermissionService.PrincipalHandle handle, long gapId) {
        return guarded(() -> {
            handle.requireEnabled();
            KnowledgeGapInputs.positiveId(gapId);
            var gap = mapper.findVisible(gapId, handle.userId());
            if (gap == null) {
                throw missing();
            }
            if (gap.id() != gapId) {
                throw new IllegalStateException("Inconsistent knowledge gap target");
            }
            requireVisibility(handle, gap);
            return gap;
        });
    }

    /** 先检查独立功能资格，后读取目标；不将写资格推导成全局 read。 */
    public KnowledgeGapRows.Gap requireForAction(PermissionService.PrincipalHandle handle, long gapId,
                                                KnowledgeGapAction action) {
        return guarded(() -> {
            requireActionPermission(handle, action);
            var gap = requireVisible(handle, gapId);
            requireAction(handle, gap, action);
            return gap;
        });
    }

    /** 新写入锁内复核和匹配重放均调用；这里不重做状态前置条件。 */
    public void requireAction(PermissionService.PrincipalHandle handle, KnowledgeGapRows.Gap gap,
                              KnowledgeGapAction action) {
        guarded(() -> {
            requireActionPermission(handle, action);
            requireVisibility(handle, gap);
            boolean reporter = gap.reporterUserId() == handle.userId();
            boolean handler = currentHandler(handle, gap);
            boolean allowed = switch (action) {
                case ASSIGN, CLOSE -> true;
                case NOTE -> reporter || handler || has(handle, Permission.KNOWLEDGE_GAP_ASSIGN);
                case RETURN, SOLUTION -> handler;
                case CONFIRM, REJECT_SOLUTION, WITHDRAW -> reporter;
                default -> false;
            };
            if (!allowed) {
                throw ApiException.forbidden("知识问题单操作被拒绝");
            }
            return null;
        });
    }

    public Capabilities capabilities(PermissionService.PrincipalHandle handle, KnowledgeGapRows.Gap gap) {
        return guarded(() -> {
            requireVisibility(handle, gap);
            boolean open = !gap.state().terminal();
            boolean reporter = gap.reporterUserId() == handle.userId();
            boolean handler = currentHandler(handle, gap);
            boolean awaiting = gap.state() == KnowledgeGapState.AWAITING_CONFIRMATION;
            boolean assign = has(handle, Permission.KNOWLEDGE_GAP_ASSIGN);
            return new Capabilities(open && assign, open && (reporter || handler || assign),
                    open && handler, gap.state() == KnowledgeGapState.IN_PROGRESS && handler,
                    awaiting && reporter, awaiting && reporter, open && reporter,
                    open && has(handle, Permission.KNOWLEDGE_GAP_CLOSE));
        });
    }

    private void requireVisibility(PermissionService.PrincipalHandle handle, KnowledgeGapRows.Gap gap) {
        handle.requireEnabled();
        if (gap.reporterUserId() != handle.userId() && !currentHandler(handle, gap)
                && !has(handle, Permission.KNOWLEDGE_GAP_READ)) {
            throw missing();
        }
    }

    private boolean currentHandler(PermissionService.PrincipalHandle handle, KnowledgeGapRows.Gap gap) {
        return Objects.equals(gap.assigneeUserId(), handle.userId()) && has(handle, Permission.KNOWLEDGE_GAP_HANDLE);
    }

    private void requireActionPermission(PermissionService.PrincipalHandle handle, KnowledgeGapAction action) {
        handle.requireEnabled();
        Permission permission = switch (Objects.requireNonNull(action)) {
            case ASSIGN -> Permission.KNOWLEDGE_GAP_ASSIGN;
            case CLOSE -> Permission.KNOWLEDGE_GAP_CLOSE;
            case RETURN, SOLUTION -> Permission.KNOWLEDGE_GAP_HANDLE;
            default -> null;
        };
        if (permission != null) {
            requirePermission(handle, permission);
        }
    }

    private void requirePermission(PermissionService.PrincipalHandle handle, Permission permission) {
        if (!has(handle, permission)) {
            throw ApiException.forbidden("知识问题单操作被拒绝");
        }
    }

    private boolean has(PermissionService.PrincipalHandle handle, Permission permission) {
        Set<String> codes = handle.functionalPermissions();
        return codes.contains(permission.code());
    }

    private static ApiException missing() {
        return ApiException.notFound("知识问题单不存在或不可访问");
    }

    private static <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (ApiException | QueryException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw ApiException.unavailable("知识问题单访问暂不可用");
        }
    }

    public record Capabilities(boolean assign, boolean note, boolean returnToReception, boolean submitSolution,
                               boolean confirm, boolean rejectSolution, boolean withdraw, boolean close) {
    }
}
