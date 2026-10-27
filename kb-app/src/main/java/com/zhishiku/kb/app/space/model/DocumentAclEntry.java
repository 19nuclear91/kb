package com.zhishiku.kb.app.space.model;

/**
 * 一条文档级例外授权，只保留回执摘要需要的两列。
 *
 * <p>与 {@link SpaceAclRecord} 的区别是故意的：搬迁的回执只需要告诉管理员
 * "哪些主体的哪个权限位被清掉了"，不需要主体显示名。少取一列就少一次按主体类型
 * 的关联子查询，而清空例外时文档上的条目可能成批存在。
 *
 * @param principalKey 主体复合键，形如 {@code "3:2"}（与 DDL 生成列一致）
 * @param permission   被清除的权限位，取值域封闭为 view / download / edit
 */
public record DocumentAclEntry(String principalKey, String permission) {

    /**
     * 回执与审计里的紧凑表示：{@code principalKey:permission}。
     */
    public String summary() {
        return principalKey + ":" + permission;
    }
}
