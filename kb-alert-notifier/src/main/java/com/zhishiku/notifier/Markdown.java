package com.zhishiku.notifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class Markdown {
    static final int BUDGET = 3500;

    private Markdown() {
    }

    record Message(String title, String text) {
        byte[] payload() {
            return BoundedJson.encode(Map.of("msgtype", "markdown", "markdown", Map.of("title", title, "text", text)));
        }
    }

    static Message render(Webhook webhook, NotifierSettings settings) {
        boolean resolved = webhook.status().equals("resolved");
        String state = resolved ? "告警条件已解除，请核对采样和当前状态" : "告警触发，请核对采样和当前状态";
        String title = truncate(escape(resolved ? "知识库告警条件已解除" : "知识库运行告警"), 80);
        long firing = webhook.alerts().stream().filter(alert -> alert.status().equals("firing")).count();
        String link = (webhook.entry().root().equals("grafana") ? settings.grafanaRoot : settings.adminRoot)
                + webhook.entry().path();
        String base = "### " + title + "\n\n" + state + "\n\n"
                + "环境：" + escape(settings.environment) + "；部署：" + escape(settings.deployment)
                + "；服务：" + escape(webhook.service()) + "；级别：" + webhook.entry().severity() + "\n\n"
                + "摘要：" + escape(webhook.entry().summary()) + "\n\n"
                + "接收 firing：" + firing + "；resolved：" + (webhook.alerts().size() - firing)
                + "；上游 truncatedAlerts：" + webhook.truncated() + "\n\n"
                + "[管理入口](" + link + ")\n\n";
        if (webhook.entry().name().startsWith("KbSettlement") || webhook.entry().path().equals("/admin/ai/audits")) {
            base += "结算以指标面板为准；AI 审计仅覆盖启用采集后的记录，缺少记录不等于无积压。\n\n";
        }
        if (webhook.entry().path().equals("/admin/identity/lifecycle")) {
            base += "生命周期告警请在授权管理页核对；未匹配、待核对和依赖未知不代表成功停权，通知不会自动恢复账号。\n\n";
        }
        List<String> lines = new ArrayList<>();
        for (Webhook.Alert alert : webhook.alerts()) {
            lines.add("- " + alert.status() + "；开始 " + alert.startsAt()
                    + (alert.status().equals("resolved") ? "；解除 " + alert.endsAt() : "") + "\n");
        }
        StringBuilder details = new StringBuilder();
        int included = 0;
        for (String line : lines) {
            // 预留最长省略计数，按转义后的 UTF-8 字节而非 UTF-16 长度预算。
            if (bytes(base + omission(100) + details + line) > BUDGET) {
                break;
            }
            details.append(line);
            included++;
        }
        String text = base + omission(lines.size() - included) + details;
        if (bytes(text) > BUDGET) {
            throw new Rejection(503, Rejection.Reason.internal);
        }
        return new Message(title, text);
    }

    private static String omission(int count) {
        return "本地省略详情：" + count + "；数量仅表示实际接收条目，不代表完整清单。\n\n";
    }

    static String escape(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(code -> {
            if (code == '&') {
                result.append("&amp;");
            } else if (code == '<') {
                result.append("&lt;");
            } else if (code == '>') {
                result.append("&gt;");
            } else {
                if ("\\`*_{}[]()#+-.!|~".indexOf(code) >= 0) {
                    result.append('\\');
                }
                result.appendCodePoint(code);
            }
        });
        return result.toString();
    }

    static String truncate(String value, int codePoints) {
        return value.substring(0, value.offsetByCodePoints(0, Math.min(codePoints, value.codePointCount(0, value.length()))));
    }

    static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
