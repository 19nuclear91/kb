package com.zhishiku.kb.app.auth.web;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoginSourceAddressTest {

    private static String resolve(String... values) {
        return LoginSourceAddress.resolve(Collections.enumeration(List.of(values)));
    }

    @Test
    void acceptsSingleIpv4Literal() {
        assertThat(resolve("10.0.0.1")).isEqualTo("10.0.0.1");
        assertThat(resolve("255.255.255.255")).isEqualTo("255.255.255.255");
        assertThat(resolve(" 192.168.1.20 ")).isEqualTo("192.168.1.20");
    }

    @Test
    void rejectsAmbiguousOrMalformedIpv4() {
        assertThat(resolve("01.2.3.4")).isNull();
        assertThat(resolve("256.1.1.1")).isNull();
        assertThat(resolve("1.2")).isNull();
        assertThat(resolve("1.2.3.4.5")).isNull();
    }

    @Test
    void equivalentIpv6SpellingsResolveToTheSameSource() {
        String expected = "2001:db8:0:0:0:0:0:1";

        assertThat(resolve("2001:DB8::1")).isEqualTo(expected);
        assertThat(resolve("2001:db8::1")).isEqualTo(expected);
        assertThat(resolve("2001:0db8:0000:0000:0000:0000:0000:0001")).isEqualTo(expected);
    }

    @Test
    void ipv4MappedIpv6CollapsesToIpv4() {
        assertThat(resolve("::ffff:1.2.3.4")).isEqualTo("1.2.3.4");
    }

    @Test
    void rejectsScopeBracketsPortsListsAndOversizedValues() {
        assertThat(resolve("fe80::1%eth0")).isNull();
        assertThat(resolve("[::1]")).isNull();
        assertThat(resolve("1.2.3.4:80")).isNull();
        assertThat(resolve("1.2.3.4, 5.6.7.8")).isNull();
        assertThat(resolve("")).isNull();
        assertThat(resolve("   ")).isNull();
        assertThat(resolve("0:".repeat(23))).isNull();
    }

    @Test
    void hostNamesAreNotResolved() {
        // 发生 DNS 解析时 localhost 会得到 127.0.0.1，返回 null 即证明没有解析。
        assertThat(resolve("localhost")).isNull();
        assertThat(resolve("example.invalid")).isNull();
    }

    @Test
    void repeatedOrMissingHeaderIsUnknown() {
        assertThat(resolve("10.0.0.1", "10.0.0.2")).isNull();
        assertThat(resolve()).isNull();
        assertThat(LoginSourceAddress.resolve(null)).isNull();
    }
}
