package com.zhishiku.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogTest extends TestSupport {
    @Test
    void completeCatalogAcceptsMultipleServicesAndOnlyFixedPaths() {
        AlertCatalog loaded = AlertCatalog.parse(BoundedJson.encode(catalog()));
        assertThat(loaded.entry("KbParseDeadLetters", "worker", "warning").services()).containsExactlyInAnyOrder("worker", "app");
        assertThat(loaded.entry("KbParseDeadLetters", "app", "warning").path()).isEqualTo("/d/kb-parse-index");
        assertThatThrownBy(() -> loaded.entry("KbParseDeadLetters", "ai", "warning")).isInstanceOf(Rejection.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "missing", "extra", "unknown-name", "unknown-service", "duplicate-service",
            "empty-services", "unknown-severity", "long-family", "long-summary", "control-summary", "arbitrary-url", "path-traversal", "root-url", "unknown-field"})
    void rejectsMalformedCatalog(String kind) {
        ArrayNode array = catalog();
        ObjectNode first = (ObjectNode) array.get(0);
        switch (kind) {
            case "duplicate" -> {
                ((ObjectNode) array.get(1)).put("name", first.get("name").textValue());
            }
            case "missing" -> {
                array.remove(0);
            }
            case "extra" -> {
                array.add(first.deepCopy());
            }
            case "unknown-name" -> {
                first.put("name", "UntrustedAlert");
            }
            case "unknown-service" -> {
                first.putArray("services").add("database");
            }
            case "duplicate-service" -> {
                first.putArray("services").add("app").add("app");
            }
            case "empty-services" -> {
                first.putArray("services");
            }
            case "unknown-severity" -> {
                first.put("severity", "info");
            }
            case "long-family" -> {
                first.put("family", "a".repeat(49));
            }
            case "long-summary" -> {
                first.put("summary", "😀".repeat(201));
            }
            case "control-summary" -> {
                first.put("summary", "标题\n不受控");
            }
            case "arbitrary-url" -> {
                ((ObjectNode) first.get("link")).put("path", "https://evil.invalid");
            }
            case "path-traversal" -> {
                ((ObjectNode) first.get("link")).put("path", "/d/../secrets");
            }
            case "root-url" -> {
                ((ObjectNode) first.get("link")).put("root", "https://evil.invalid");
            }
            case "unknown-field" -> {
                first.put("template", "arbitrary");
            }
            default -> {
                throw new IllegalArgumentException();
            }
        }
        assertThatThrownBy(() -> AlertCatalog.parse(BoundedJson.encode(array))).isInstanceOf(Rejection.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.invalid", "www.evil.invalid"})
    void summaryCannotSmuggleAnUncontrolledUrl(String value) {
        ArrayNode items = catalog();
        ((ObjectNode) items.get(0)).put("summary", value);
        assertThatThrownBy(() -> AlertCatalog.parse(BoundedJson.encode(items))).isInstanceOf(Rejection.class);
    }
}
