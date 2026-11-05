package com.zhishiku.notifier;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

record Webhook(String status, String service, AlertCatalog.Entry entry, List<Alert> alerts, long truncated) {
    record Alert(String status, Instant startsAt, Instant endsAt) {
    }

    static Webhook decode(byte[] bytes, NotifierSettings settings) {
        JsonNode root = BoundedJson.parse(bytes, BoundedJson.REQUEST_BYTES);
        if (!root.isObject() || !"4".equals(BoundedJson.text(root, "version"))) {
            throw Rejection.invalid();
        }
        String status = status(root);
        BoundedJson.text(root, "receiver");
        BoundedJson.text(root, "groupKey");
        JsonNode group = stringMap(root, "groupLabels");
        JsonNode common = stringMap(root, "commonLabels");
        stringMap(root, "commonAnnotations");
        String name = BoundedJson.text(group, "alertname");
        String service = BoundedJson.text(group, "service");
        identity(group, settings);
        JsonNode items = root.get("alerts");
        JsonNode truncated = root.get("truncatedAlerts");
        long truncatedCount = 0;
        if (truncated != null) {
            if (!truncated.isIntegralNumber() || !truncated.canConvertToLong() || truncated.longValue() < 0) {
                throw Rejection.invalid();
            }
            truncatedCount = truncated.longValue();
        }
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw Rejection.invalid();
        }
        if (items.size() > 100) {
            throw new Rejection(413, Rejection.Reason.too_large);
        }
        List<Alert> alerts = new ArrayList<>();
        AlertCatalog.Entry entry = null;
        int firing = 0;
        for (JsonNode item : items) {
            JsonNode labels = stringMap(item, "labels");
            stringMap(item, "annotations");
            identity(labels, settings);
            if (!name.equals(BoundedJson.text(labels, "alertname")) || !service.equals(BoundedJson.text(labels, "service"))) {
                throw Rejection.invalid();
            }
            AlertCatalog.Entry current = settings.catalog.entry(name, service, BoundedJson.text(labels, "severity"));
            if (labels.has("family") && !current.family().equals(BoundedJson.text(labels, "family"))) {
                throw Rejection.invalid();
            }
            for (String key : List.of("environment", "deployment", "service", "alertname", "severity", "family")) {
                if (common.has(key) && !common.get(key).equals(labels.get(key))) {
                    throw Rejection.invalid();
                }
                if (group.has(key) && !group.get(key).equals(labels.get(key))) {
                    throw Rejection.invalid();
                }
            }
            entry = current;
            String itemStatus = status(item);
            Instant starts = time(item, "startsAt");
            Instant ends = time(item, "endsAt");
            Instant zero = Instant.parse("0001-01-01T00:00:00Z");
            if (!starts.isAfter(zero) || (itemStatus.equals("resolved") && ends.isBefore(starts))
                    || (!ends.equals(zero) && ends.isBefore(starts))) {
                throw Rejection.invalid();
            }
            if (itemStatus.equals("firing")) {
                firing++;
            }
            alerts.add(new Alert(itemStatus, starts, ends));
        }
        if ((status.equals("resolved") && firing != 0) || (status.equals("firing") && firing == 0 && truncatedCount == 0)) {
            throw Rejection.invalid();
        }
        return new Webhook(status, service, entry, List.copyOf(alerts), truncatedCount);
    }

    private static JsonNode stringMap(JsonNode parent, String name) {
        JsonNode object = BoundedJson.object(parent, name);
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            if (!fields.next().getValue().isTextual()) {
                throw Rejection.invalid();
            }
        }
        return object;
    }

    private static void identity(JsonNode labels, NotifierSettings settings) {
        if (!settings.environment.equals(BoundedJson.text(labels, "environment"))
                || !settings.deployment.equals(BoundedJson.text(labels, "deployment"))) {
            throw Rejection.invalid();
        }
    }

    private static String status(JsonNode object) {
        String value = BoundedJson.text(object, "status");
        if (!value.equals("firing") && !value.equals("resolved")) {
            throw Rejection.invalid();
        }
        return value;
    }

    private static Instant time(JsonNode item, String name) {
        String text = BoundedJson.text(item, name);
        if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})")) {
            throw Rejection.invalid();
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException exception) {
            throw Rejection.invalid();
        }
    }
}
