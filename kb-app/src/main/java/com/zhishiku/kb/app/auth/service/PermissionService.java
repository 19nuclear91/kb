package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PermissionScope;
import com.zhishiku.kb.common.security.PrincipalType;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 把"一个用户 ID"解析成可直接下推到 SQL 的 {@link PermissionScope}。
 *
 * <p>这是整个系统的权限入口。检索、预览、下载、AI 上下文都必须经过它，
 * 不允许任何路径自行拼权限条件（见《架构决策记录.md》4.5）。
 *
 * <p><b>请求内复用（不是缓存）</b>：同一调用栈内需要多个权限位或多次功能权限
 * 判定时，先用 {@link #principalOf(long)} 铸造一个 {@link PrincipalHandle}，
 * 再把它交给 {@link #resolve(PrincipalHandle, String)} 与句柄上的判定入口——
 * 身份事实与功能权限码因此只查询一次。复用范围由类型结构限定在调用栈内：
 * 句柄构造器私有（不可伪造），且把它存为字段会被 ModuleBoundaryTest 的
 * ArchUnit 规则在构建期拦截。因此每次判定读到的仍是当次请求的数据库事实，
 * 不触碰 functional-authorization 与 identity-department 的即时生效要求。
 *
 * <p><b>跨请求缓存仍未实现，且不能随意引入</b>：缓存快照与上述规格
 * 「变更必须在下一次请求即生效」的要求直接冲突，引入前必须先修订该要求，
 * 并确定可接受的撤销延迟。
 */
@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    /**
     * 检索与 RAG 看 view；下载走 download，两者不可混用。
     */
    public static final String PERM_VIEW = "view";
    public static final String PERM_DOWNLOAD = "download";
    public static final String PERM_EDIT = "edit";
    /** 仅为空间审核池权限，不隐含 view、download、edit 或足够密级。 */
    public static final String PERM_REVIEW = "review";
    /** 仅为发表评论资格，空间与文档例外取并集；不隐含 view、download、edit 或足够密级。 */
    public static final String PERM_COMMENT = "comment";

    /**
     * 空间管理权限位。
     *
     * <p>持有某空间的 manage 即可管理该空间的授权名单。<b>注意两条反直觉语义</b>：
     * <ul>
     *   <li>manage 的分量实际等同于该空间上的全部权限——持有者可以把 edit
     *       授予自己所在的部门，从而获得 edit。因此授出 manage 等同于授出
     *       edit + download。</li>
     *   <li>manage <b>不</b>蕴含 view。两者是独立的 ACL 记录，因此存在
     *       "能管名单但读不了内容"的操作者。这是职责分离的有意结果。</li>
     * </ul>
     *
     * <p>与读权限位的另一处差异：{@code manage} 的可见空间集合<b>不</b>排除
     * 已归档空间，否则被委派的管理员将无法取消归档。
     */
    public static final String PERM_MANAGE = "manage";

    private final PermissionMapper mapper;
    private final IdentityService identityService;
    private final RolePermissionMapper rolePermissionMapper;

    public PermissionService(PermissionMapper mapper) {
        this(mapper, null, null);
    }

    public PermissionService(PermissionMapper mapper, IdentityService identityService) {
        this(mapper, identityService, null);
    }

    @Autowired
    public PermissionService(PermissionMapper mapper, IdentityService identityService,
                             RolePermissionMapper rolePermissionMapper) {
        this.mapper = mapper;
        this.identityService = identityService;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    /**
     * 解析指定用户在给定权限位下的可见范围。
     *
     * @param userId     <b>必须</b>来自服务端已验证的身份。绝不能采信请求体或请求头里
     *                   客户端自称的用户 / 角色 / 部门。
     * @param permission {@link #PERM_VIEW}、{@link #PERM_DOWNLOAD}、{@link #PERM_EDIT}、
     *                   {@link #PERM_REVIEW}、{@link #PERM_COMMENT} 或 {@link #PERM_MANAGE}
     */
    public PermissionScope resolve(long userId, String permission) {
        return resolve(principalOf(userId), permission);
    }

    /**
     * 铸造主体句柄：一次调用栈内复用身份事实的唯一入口。
     *
     * <p>与 {@link #resolve(long, String)} 一样，解析失败降级为失败态句柄而不是
     * 抛异常，供检索、文档读取等"拿不到身份就看不到任何东西"的路径使用；
     * 需要区分"停用"与"无权限"的路径（如空间管理资格）随后调用
     * {@link PrincipalHandle#requireEnabled()} 取回原始异常。
     *
     * <p>句柄只能由本方法铸造，且<b>不得</b>存为字段或跨请求传递。
     *
     * @param userId <b>必须</b>来自服务端已验证的身份，不接受客户端自称的标识
     */
    public PrincipalHandle principalOf(long userId) {
        if (identityService != null) {
            try {
                ResolvedPrincipal principal = Objects.requireNonNull(
                        identityService.resolvePrincipal(userId),
                        "resolvePrincipal 返回 null；契约要求返回主体或抛出 IdentityException");
                return PrincipalHandle.enabled(userId, principal, rolePermissionMapper);
            } catch (IdentityException e) {
                log.warn("主体解析失败 stage=identity category={} status={}", e.errorCode(), e.httpStatus());
                return PrincipalHandle.failed(userId, e);
            }
        }
        Short clearance = mapper.findClearanceLevel(userId);
        if (clearance == null) {
            // 用户不存在或已停用：进入失败态，resolve 会降级为"看不到任何东西"，
            // 而不是抛异常后被上层吞掉当成放行
            log.warn("主体解析失败 stage=identity category=DISABLED status=403");
            return PrincipalHandle.failed(userId, IdentityException.disabled("用户不存在或已停用"));
        }
        return PrincipalHandle.enabled(userId, clearance, mapper.findRoleIds(userId),
                mapper.findDeptChainIds(userId), rolePermissionMapper);
    }

    /**
     * 用句柄解析指定权限位的可见范围；失败态句柄降级为 {@link PermissionScope#denyAll(long)}。
     *
     * <p>这是两条装配路径（有无 {@code identityService}）唯一的 {@code PermissionScope}
     * 构造位置，避免语义漂移。每个权限位只发出一次 {@code findVisibleSpaceIds}。
     *
     * @param handle     {@link #principalOf(long)} 铸造的句柄；不接受其他来源
     * @param permission {@link #PERM_VIEW}、{@link #PERM_DOWNLOAD}、{@link #PERM_EDIT}、
     *                   {@link #PERM_REVIEW}、{@link #PERM_COMMENT} 或 {@link #PERM_MANAGE}
     */
    public PermissionScope resolve(PrincipalHandle handle, String permission) {
        Objects.requireNonNull(handle, "主体句柄不可为 null；请先调用 principalOf(userId)");
        if (handle.failure != null) {
            return PermissionScope.denyAll(handle.userId);
        }
        List<Long> visibleSpaces = handle.principalKeys.isEmpty()
                ? List.of()
                : mapper.findVisibleSpaceIds(new ArrayList<>(handle.principalKeys), permission);
        PermissionScope scope = PermissionScope.of(
                handle.userId, handle.clearanceLevel, visibleSpaces, handle.principalKeys);
        log.debug("权限解析完成: {} permission={}", scope, permission);
        return scope;
    }

    /**
     * 主体句柄：一次调用栈内复用身份事实与功能权限码的载体。
     *
     * <p>两种状态二择一：
     * <ul>
     *   <li><b>成功态</b>：携带密级、角色与部门链（{@code principalKeys} 在铸造时
     *       预计算），{@link #requireEnabled()} 为无操作；</li>
     *   <li><b>失败态</b>：携带解析失败时捕获的 {@link IdentityException}，
     *       {@link #requireEnabled()} 重新抛出它，其余方法给出安全的拒绝默认值。</li>
     * </ul>
     *
     * <p>构造器私有：句柄只能由 {@link PermissionService#principalOf(long)} 铸造，
     * 不存在用客户端数据拼出一个虚高主体的入口（{@link ResolvedPrincipal} 本身
     * 也不从句柄暴露）。<b>句柄不得存为字段</b>——那会把调用栈内复用变成跨请求
     * 缓存；该约束由 ModuleBoundaryTest 的 ArchUnit 规则在构建期拦截。
     */
    public static final class PrincipalHandle {

        private final long userId;
        private final short clearanceLevel;
        private final Set<String> principalKeys;
        private final IdentityException failure;
        private final RolePermissionMapper rolePermissionMapper;
        private Set<String> functionalPermissions;

        private PrincipalHandle(long userId, short clearanceLevel, Set<String> principalKeys,
                                IdentityException failure, RolePermissionMapper rolePermissionMapper) {
            this.userId = userId;
            this.clearanceLevel = clearanceLevel;
            this.principalKeys = principalKeys;
            this.failure = failure;
            this.rolePermissionMapper = rolePermissionMapper;
        }

        private static PrincipalHandle enabled(long userId, ResolvedPrincipal principal,
                                               RolePermissionMapper rolePermissionMapper) {
            return enabled(userId, principal.clearanceLevel(), principal.roleIds(),
                    principal.departmentChainIds(), rolePermissionMapper);
        }

        private static PrincipalHandle enabled(long userId, short clearanceLevel,
                                               List<Long> roleIds, List<Long> deptChainIds,
                                               RolePermissionMapper rolePermissionMapper) {
            Set<String> keys = new LinkedHashSet<>();
            keys.add(PermissionScope.principalKey(PrincipalType.USER, userId));
            keys.addAll(PermissionScope.principalKeys(PrincipalType.ROLE, roleIds));
            keys.addAll(PermissionScope.principalKeys(PrincipalType.DEPT, deptChainIds));
            return new PrincipalHandle(userId, clearanceLevel, keys, null, rolePermissionMapper);
        }

        private static PrincipalHandle failed(long userId, IdentityException failure) {
            return new PrincipalHandle(userId, (short) 0, Set.of(), failure, null);
        }

        /**
         * 服务端已验证的用户 ID；失败态下是铸造时传入的 ID，仅用于 {@code denyAll}。
         */
        public long userId() {
            return userId;
        }

        /**
         * 成功态无操作；失败态重新抛出铸造时捕获的 {@link IdentityException}，
         * 让"停用"不被 {@link PermissionService#resolve(PrincipalHandle, String)}
         * 的 {@code denyAll} 降级吞掉。
         */
        public void requireEnabled() {
            if (failure != null) {
                throw failure;
            }
        }

        /**
         * 功能权限码集合；首次访问查询一次并在句柄内保留，失败态返回空集
         * （拒绝默认值，与 {@link PermissionScope#denyAll(long)} 的取向一致）。
         *
         * <p>复用范围仅限本句柄所在的一次调用栈——两次独立 {@code principalOf}
         * 各自查询，与 functional-authorization「不得来自缓存快照」一致。
         */
        public Set<String> functionalPermissions() {
            if (failure != null) {
                return Set.of();
            }
            Set<String> loaded = functionalPermissions;
            if (loaded == null) {
                if (rolePermissionMapper == null) {
                    throw new IllegalStateException(
                            "功能权限判定需要 RolePermissionMapper；请使用含它的构造器装配 PermissionService");
                }
                var codes = rolePermissionMapper.findUserPermissions(userId);
                loaded = codes == null ? Set.of() : Set.copyOf(codes);
                functionalPermissions = loaded;
            }
            return loaded;
        }
    }
}
