package com.zhishiku.kb.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 本地凭据哈希配置。
 *
 * <p>只引入 spring-security-crypto，不引入完整 Spring Security：
 * 会话体系由 Sa-Token 承担，完整 starter 会带来与之重叠的过滤器链，
 * 而这里需要的只是哈希原语。编码器抽象隔离了算法，将来可整体替换为
 * PBKDF2 等实现而不影响调用方。
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * 用户名不存在时用于比对的固定假哈希（成本 10，明文为随机值、无人知晓）。
     *
     * <p>登录对未知用户也执行一次真实 bcrypt 比对，使“用户不存在”与
     * “密码错误”的耗时相当，收敛用户名枚举的时间侧信道；比对结果被丢弃，
     * 因此任何候选口令都不可能匹配成功。它必须始终是一段结构合法的
     * bcrypt 哈希，否则比对被提前短路，耗时差异又会回来。
     */
    public static final String DUMMY_PASSWORD_HASH =
            "$2a$10$PojIogOXt0ySAJbGcDT5rOKQUJo68ww7LPpy5IB36juwp.MO5YrEi";

    @Bean
    public BCryptPasswordEncoder passwordEncoder(
            @Value("${kb.auth.bcrypt-strength:10}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }
}
