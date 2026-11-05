package com.zhishiku.notifier;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class DingTalkProtocol {
    static final String TARGET = "https://oapi.dingtalk.com/robot/send";

    private DingTalkProtocol() {
    }

    static String signature(long timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8));
            return URLEncoder.encode(Base64.getEncoder().encodeToString(digest), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new Rejection(503, Rejection.Reason.internal);
        }
    }

    static URI target(long timestamp, NotifierSettings settings) {
        return URI.create(TARGET + "?access_token=" + URLEncoder.encode(settings.token(), StandardCharsets.UTF_8)
                + "&timestamp=" + timestamp + "&sign=" + signature(timestamp, settings.signingSecret()));
    }
}
