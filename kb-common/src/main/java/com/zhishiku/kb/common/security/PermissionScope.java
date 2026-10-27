package com.zhishiku.kb.common.security;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 已解析的权限上下文，是所有检索与读取路径的<b>唯一</b>权限入参。
 *
 * <p>设计意图（对应《架构决策记录.md》4.3、4.5）：
 * <ul>
 *   <li><b>权限下推</b>：把权限变成 SQL 的 WHERE 条件，而不是"先召回再过滤"。
 *       后者会让 top-k 里大部分被丢弃，召回质量断崖下跌。</li>
 *   <li><b>不可能构造出"无过滤"的实例</b>：空主体集合就是"什么都看不到"，
 *       绝不会退化成"看到全部"。这是本类存在的主要理由。</li>
 * </ul>
 *
 * <p>本类是不可变值对象。请求内的复用（同一次调用栈内多个权限位只解析一次主体）
 * 由应用层的主体句柄承担，寿命不超过一次调用栈，因此不构成缓存。
 * <b>跨请求缓存尚未实现，且不能随意引入</b>：缓存快照与 functional-authorization、
 * identity-department 两条规格「变更必须在下一次请求即生效」的要求直接冲突，
 * 引入前必须先修订该要求，并确定可接受的撤销延迟。
 */
public final class PermissionScope {

    private final long userId;
    private final short clearanceLevel;
    private final Long[] visibleSpaceIds;
    private final String[] principalKeys;

    private PermissionScope(long userId,
                            short clearanceLevel,
                            Long[] visibleSpaceIds,
                            String[] principalKeys) {
        this.userId = userId;
        this.clearanceLevel = clearanceLevel;
        this.visibleSpaceIds = visibleSpaceIds;
        this.principalKeys = principalKeys;
    }

    /**
     * 构造权限上下文。
     *
     * @param userId          当前用户；必须来自服务端已验证的身份，
     *                        <b>绝不能采信客户端传入的用户/角色标识</b>
     * @param clearanceLevel  用户可见的最高密级
     * @param visibleSpaceIds 该用户在<b>所请求的那个权限位</b>上命中的空间（由 ACL 预计算，可缓存）。
     *                        本类不记录是哪个权限位，因此同一用户在 view 与 manage 下
     *                        会得到两个不同的实例，调用方不得混用
     * @param principalKeys   有效主体复合键，形如 {@code "1:42"}(用户) / {@code "2:7"}(角色)
     *                        / {@code "3:2"}(部门)，与 DDL 中的生成列 principal_key 对应
     */
    public static PermissionScope of(long userId,
                                     short clearanceLevel,
                                     Collection<Long> visibleSpaceIds,
                                     Collection<String> principalKeys) {
        Objects.requireNonNull(visibleSpaceIds, "visibleSpaceIds 不可为 null（空集合表示看不到任何空间）");
        Objects.requireNonNull(principalKeys, "principalKeys 不可为 null（空集合表示无任何主体）");
        if (clearanceLevel < 0) {
            throw new IllegalArgumentException("clearanceLevel 不能为负：" + clearanceLevel);
        }
        return new PermissionScope(
                userId,
                clearanceLevel,
                visibleSpaceIds.stream().distinct().toArray(Long[]::new),
                principalKeys.stream().distinct().toArray(String[]::new));
    }

    /**
     * 构造"什么都看不到"的上下文。
     *
     * <p>用于未登录、身份解析失败等场景。存在这个显式工厂，是为了让调用方在
     * 拿不到身份时有一个<b>安全的默认值</b>可用，而不是图省事传 null 或跳过过滤。
     */
    public static PermissionScope denyAll(long userId) {
        return new PermissionScope(userId, (short) 0, new Long[0], new String[0]);
    }

    /**
     * 把主体集合编码成与 DDL 生成列一致的复合键。
     *
     * <p>编码规则必须与 V1 迁移中
     * {@code principal_key TEXT GENERATED ALWAYS AS (principal_type || ':' || principal_id)}
     * 完全一致，否则权限会静默失效——这类 bug 不会报错，只会让人看到不该看的东西。
     */
    public static String principalKey(PrincipalType type, long id) {
        return type.code() + ":" + id;
    }

    public static Set<String> principalKeys(PrincipalType type, Collection<Long> ids) {
        return ids.stream().map(id -> principalKey(type, id)).collect(Collectors.toSet());
    }

    /**
     * 是否完全没有任何可见范围。
     *
     * <p>调用方可据此短路，省掉一次必然返回空的数据库往返。
     * 但<b>不要</b>反过来用它做"如果非空就跳过过滤"之类的判断。
     */
    public boolean seesNothing() {
        return visibleSpaceIds.length == 0 && principalKeys.length == 0;
    }

    public long userId() {
        return userId;
    }

    public short clearanceLevel() {
        return clearanceLevel;
    }

    /**
     * 返回副本，避免调用方改动内部状态
     */
    public Long[] visibleSpaceIds() {
        return visibleSpaceIds.clone();
    }

    /**
     * 返回副本，避免调用方改动内部状态
     */
    public String[] principalKeys() {
        return principalKeys.clone();
    }

    public List<Long> visibleSpaceIdList() {
        return List.of(visibleSpaceIds);
    }

    @Override
    public String toString() {
        // 不打印具体 ID 列表，避免权限拓扑进日志
        return "PermissionScope{userId=" + userId
                + ", clearance=" + clearanceLevel
                + ", spaces=" + visibleSpaceIds.length
                + ", principals=" + principalKeys.length + "}";
    }
}
