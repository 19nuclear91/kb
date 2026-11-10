package com.zhishiku.kb.app.auth.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.regex.Pattern;

/**
 * 登录来源地址：只采信入口 Nginx 写入的 {@code X-Real-IP}。
 *
 * <p>只接受单个 IPv4/IPv6 字面量。先做字符级校验，再交给 JDK 解析：
 * 主机名、旧式 IPv4 写法、scope id、端口与方括号一律视为未知，
 * 因此不会触发域名解析。结果取 {@code getHostAddress()}，同一地址的不同写法得到同一字符串；
 * 未知时返回 null，调用方据此跳过来源维度。
 */
final class LoginSourceAddress {

    static final String HEADER = "X-Real-IP";

    /** IPv6 内嵌 IPv4 的最长字面量为 45 个字符。 */
    private static final int MAX_LENGTH = 45;

    private static final String OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");
    /** 首字符必须是十六进制数字或冒号，JDK 才会按字面量解析而不是查询 DNS。 */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]*");

    private LoginSourceAddress() {
    }

    /**
     * 把 {@code X-Real-IP} 解析为规范化的来源地址。
     *
     * @param values {@code X-Real-IP} 的全部头值；容器不允许读取请求头时可能为 null
     * @return 规范化后的来源地址；缺失、多值或非法时为 null
     */
    static String resolve(Enumeration<String> values) {
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String raw = values.nextElement();
        if (raw == null || values.hasMoreElements()) {
            return null;
        }
        String candidate = raw.strip();
        if (candidate.isEmpty() || candidate.length() > MAX_LENGTH) {
            return null;
        }
        boolean literal = IPV4.matcher(candidate).matches()
                || (candidate.indexOf(':') >= 0 && IPV6.matcher(candidate).matches());
        if (!literal) {
            return null;
        }
        try {
            return InetAddress.getByName(candidate).getHostAddress();
        } catch (UnknownHostException invalid) {
            return null;
        }
    }
}
