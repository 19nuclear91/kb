package com.zhishiku.kb.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 密码强度策略（设计 D5）。
 *
 * <p>只约束长度，不引入字符类别复杂度要求：复杂度规则会逼出可预测的
 * 变形（尾部加 1、首字母大写），对防爆破帮助有限，却显著增加支持成本。
 * 需要收紧时调大本值即可。
 *
 * @param minLength 新密码最小长度；初始密码、自助改密与管理员重置共用
 */
@ConfigurationProperties(prefix = "kb.auth.password")
public record AuthPasswordProperties(int minLength) {

    public AuthPasswordProperties {
        if (minLength <= 0) {
            minLength = 8;
        }
    }
}
