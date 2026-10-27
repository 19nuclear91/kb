package com.zhishiku.kb.app.auth.sso;

import java.util.Map;

/**
 * 三家浏览器协议各自核验，编排层不接纳客户端身份声明。
 */
public interface ExternalLoginProvider {
    String platform();

    String authorizationUrl(String state, String verifier);

    VerifiedExternalIdentity verify(Map<String, String> parameters, String verifier);
}
