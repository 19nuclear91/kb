package com.zhishiku.kb.app.embedding.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.embedding.model.EmbeddingCommand;
import com.zhishiku.kb.app.embedding.model.EmbeddingReceipt;
import com.zhishiku.kb.app.embedding.service.EmbeddingAuthorizer;
import com.zhishiku.kb.app.embedding.service.EmbeddingCommandService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/embedding/rebuilds")
public class EmbeddingCommandController {
    private final EmbeddingAuthorizer authorizer;
    private final EmbeddingCommandService service;

    public EmbeddingCommandController(EmbeddingAuthorizer authorizer, EmbeddingCommandService service) {
        this.authorizer = authorizer;
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<R<EmbeddingReceipt>> start(@RequestBody JsonNode body,
                                                     @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long actor = authorizer.require(Permission.EMBEDDING_REBUILD);
        checkFields(body, Set.of("profileId", "expectedEpoch", "reason", "requestId"));
        var command = new EmbeddingCommand("START", text(body, "profileId", true), null,
                version(body, "expectedEpoch"), text(body, "reason", true),
                EmbeddingCommand.requestId(text(body, "requestId", false), requestId));
        return ResponseEntity.accepted().body(R.ok(service.execute(actor, command)));
    }

    @PostMapping("/{runId}/resume")
    public ResponseEntity<R<EmbeddingReceipt>> resume(@PathVariable UUID runId, @RequestBody JsonNode body,
                                                      @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long actor = authorizer.require(Permission.EMBEDDING_REBUILD);
        checkFields(body, Set.of("expectedVersion", "reason", "requestId"));
        var command = new EmbeddingCommand("RESUME", null, runId, version(body, "expectedVersion"),
                text(body, "reason", true), EmbeddingCommand.requestId(text(body, "requestId", false), requestId));
        return ResponseEntity.accepted().body(R.ok(service.execute(actor, command)));
    }

    private static void checkFields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw ApiException.badRequest("invalid embedding request");
        body.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw ApiException.badRequest("unknown embedding request field");
        });
    }

    private static String text(JsonNode body, String key, boolean required) {
        var value = body.get(key);
        if (value == null && !required) return null;
        if (value == null || !value.isTextual()) throw ApiException.badRequest("invalid embedding request field");
        return value.textValue();
    }

    private static long version(JsonNode body, String key) {
        var value = body.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw ApiException.badRequest("invalid expected embedding version");
        }
        return value.longValue();
    }
}
