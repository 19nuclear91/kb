package com.zhishiku.notifier;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class AlertCatalog {
    static final Set<String> NAMES = Set.of("KbServiceUnavailable", "KbServiceReplicaUnavailable",
            "KbLifecycleInputRejected", "KbLifecycleEventConflict", "KbLifecycleRevocationSlow",
            "KbLifecycleDisconnected", "KbLifecycleBacklogOld", "KbLifecycleEmergencyUnavailable",
            "KbOutputPermitOverdue", "KbLifecycleDependencyFailures", "KbRevocationTargetUnconfirmed",
            "KbMonitoringContractMissing", "KbMetricSampleUnavailable", "KbParserNotConfigured",
            "KbParseQueueAging", "KbParseDeadLetters", "KbParseLeaseLoss", "KbEmbeddingBlocked",
            "KbEmbeddingMaintenanceLong", "KbSearchFailures", "KbSearchRetrievalSlow", "KbSearchDegradation",
            "KbModelFailures", "KbChatSystemErrors", "KbSettlementPendingOld", "KbSettlementReview",
            "KbConversationConfigurationMismatch", "KbNotificationDeliveryFailures", "KbRuleEvaluationFailures");
    static final Set<String> SERVICES = Set.of("app", "worker", "ai", "gateway", "notifier",
            "prometheus", "alertmanager", "grafana");
    private static final Map<String, Set<String>> PATHS = Map.of(
            "admin", Set.of("/admin/parse/dead-letters", "/admin/embedding", "/admin/ai/audits", "/admin/identity/lifecycle"),
            "grafana", Set.of("/d/kb-overview", "/d/kb-parse-index", "/d/kb-search-ai"));
    private final Map<String, Entry> entries;

    record Entry(String name, Set<String> services, String severity, String family, String summary,
            String root, String path) {
    }

    private AlertCatalog(Map<String, Entry> entries) {
        this.entries = Map.copyOf(entries);
    }

    static AlertCatalog parse(byte[] bytes) {
        JsonNode root = BoundedJson.parse(bytes, 64 * 1024);
        if (!root.isArray() || root.size() != NAMES.size()) {
            throw Rejection.invalid();
        }
        Map<String, Entry> entries = new HashMap<>();
        for (JsonNode item : root) {
            if (!item.isObject() || item.size() != 6) {
                throw Rejection.invalid();
            }
            String name = BoundedJson.text(item, "name");
            String severity = BoundedJson.text(item, "severity");
            String family = BoundedJson.text(item, "family");
            String summary = BoundedJson.text(item, "summary");
            JsonNode services = item.get("services");
            JsonNode link = BoundedJson.object(item, "link");
            String linkRoot = BoundedJson.text(link, "root");
            String path = BoundedJson.text(link, "path");
            if (!NAMES.contains(name) || !(severity.equals("warning") || severity.equals("critical"))
                    || !family.matches("[a-z][a-z0-9_-]{0,47}")
                    || summary.codePointCount(0, summary.length()) > 200
                    || summary.codePoints().anyMatch(Character::isISOControl)
                    || summary.contains("://") || summary.toLowerCase(java.util.Locale.ROOT).contains("www.")
                    || services == null || !services.isArray() || services.isEmpty() || services.size() > SERVICES.size()
                    || link.size() != 2 || !PATHS.getOrDefault(linkRoot, Set.of()).contains(path)) {
                throw Rejection.invalid();
            }
            Set<String> allowed = new HashSet<>();
            for (JsonNode service : services) {
                if (!service.isTextual() || !SERVICES.contains(service.textValue()) || !allowed.add(service.textValue())) {
                    throw Rejection.invalid();
                }
            }
            if (entries.put(name, new Entry(name, Set.copyOf(allowed), severity, family, summary, linkRoot, path)) != null) {
                throw Rejection.invalid();
            }
        }
        return new AlertCatalog(entries);
    }

    Entry entry(String name, String service, String severity) {
        Entry entry = entries.get(name);
        if (entry == null || !entry.services().contains(service) || !entry.severity().equals(severity)) {
            throw Rejection.invalid();
        }
        return entry;
    }
}
