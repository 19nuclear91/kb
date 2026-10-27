package com.zhishiku.kb.app.space.model;

/**
 * 在某个空间持有 {@code view} 的一个授权主体。
 *
 * <p>搬迁回执用它展开目标空间的受众，再逐个判定密级是否足够。只带类型与 ID，
 * 因为密级是用户的属性，不是主体的——判定必须先把主体落到具体用户上。
 *
 * @param principalType 主体类型码，与 {@code PrincipalType} 一致（1=用户 2=角色 3=部门）
 * @param principalId   主体内部 ID
 */
public record SpaceViewPrincipal(short principalType, long principalId) {
}
