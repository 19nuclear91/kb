package com.zhishiku.kb.app.auth.service;

import cn.dev33.satoken.stp.StpInterface;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 为 Sa-Token 的角色查询保留服务端持久化来源，不把角色写入共享会话。
 *
 * <p>管理端点已改由 IdentityManagementAuthorizer 精确匹配功能权限；
 * 此组件只返回角色 code，permission API 保持禁用。资源可见性继续按 SQL 中的 ACL 与密级过滤。
 */
@Component
public class SaTokenRoleProvider implements StpInterface {

    private static final Logger log = LoggerFactory.getLogger(SaTokenRoleProvider.class);

    private final PermissionMapper permissionMapper;

    public SaTokenRoleProvider(PermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    /**
     * 返回该登录身份在服务端持有的角色 code。
     *
     * <p>走 {@link PermissionMapper#findRoleCodes(long)} 而<b>不是</b>
     * {@code findRoleIds}：前者连 {@code sys_role} 取 code，并且过滤已停用用户。
     * 后者返回数字 ID，是 ACL 主体键用的，拿到这里来判定功能资格永远不命中。
     *
     * <p>停用过滤在查询侧，因此仍持有旧会话的停用用户不会返回任何角色。
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = parseUserId(loginId);
        if (userId == null) {
            return List.of();
        }
        List<String> codes = permissionMapper.findRoleCodes(userId);
        return codes == null ? List.of() : codes;
    }

    /**
     * 本系统不使用 Sa-Token 的 permission 语义，固定返回空列表。
     *
     * <p><b>这是一颗需要看住的哑雷。</b>当前没有任何代码调用
     * {@code checkPermission} / {@code hasPermission}，所以返回空是正确的。
     * 但一旦有人写下第一行 {@code StpUtil.checkPermission("doc:read")}，
     * 他会得到<b>静默恒假</b>的判定——与本类要修的缺陷完全同形。
     *
     * <p>因此 {@code SaTokenPermissionApiGuardTest} 断言生产代码中不出现这些调用。
     * 真要引入 permission 语义，先让那个测试失败，再在这里实现真实来源，
     * 不要绕过它。
     *
     * <p>细粒度的文档与空间权限不走这条路：它们下推到 SQL 里按 ACL 与密级过滤
     * （见 {@link PermissionService}），不是框架层的字符串权限。
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.of();
    }

    /**
     * 把 Sa-Token 交回的登录标识解析成内部用户 ID。
     *
     * <p>会话经 Redis 往返后 {@code loginId} 可能是 {@code String} 而非
     * {@code Long}，所以统一按文本解析，不做类型假设。
     *
     * <p>解析失败时返回 {@code null}（调用方据此给出空角色），<b>而不是抛异常</b>：
     * 从 {@link StpInterface} 抛出会变成 500，把一次"无资格"伪装成服务故障，
     * 调用方拿不到应有的 403。空角色即拒绝，与
     * {@code PermissionScope.denyAll} 的安全默认一致。
     */
    private Long parseUserId(Object loginId) {
        if (loginId == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(loginId).trim());
        } catch (NumberFormatException e) {
            log.warn("无法把登录标识解析为用户 ID，按无角色处理: loginId 类型={}",
                    loginId.getClass().getName());
            return null;
        }
    }
}
