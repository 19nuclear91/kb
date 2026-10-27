package com.zhishiku.kb.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderConfigTest {

    private final PasswordEncoderConfig config = new PasswordEncoderConfig();

    @Test
    void strengthComesFromConfiguration() {
        BCryptPasswordEncoder strength4 = config.passwordEncoder(4);
        String hash = strength4.encode("s3cret-pass");
        assertThat(hash).startsWith("$2a$04$");
        assertThat(strength4.matches("s3cret-pass", hash)).isTrue();

        assertThat(config.passwordEncoder(10).encode("s3cret-pass")).startsWith("$2a$10$");
    }

    @Test
    void dummyHashIsAWellFormedBcryptHash() {
        assertThat(PasswordEncoderConfig.DUMMY_PASSWORD_HASH)
                .startsWith("$2a$10$")
                .hasSize(60);
        assertThat(PasswordEncoderConfig.DUMMY_PASSWORD_HASH.substring(7))
                .matches("[./0-9A-Za-z]{53}");
    }

    @Test
    void dummyHashNeverMatchesAnyCandidate() {
        BCryptPasswordEncoder encoder = config.passwordEncoder(10);
        for (String candidate : new String[]{"", "password", "123456", "admin", "系统管理员"}) {
            assertThat(encoder.matches(candidate, PasswordEncoderConfig.DUMMY_PASSWORD_HASH))
                    .as("候选口令不应匹配假哈希")
                    .isFalse();
        }
    }
}
