package com.zhishiku.kb.app.embedding.web;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.embedding.model.EmbeddingViews;
import com.zhishiku.kb.app.embedding.service.EmbeddingAdminReadService;
import com.zhishiku.kb.app.embedding.service.EmbeddingAuthorizer;
import com.zhishiku.kb.common.api.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/embedding")
public class EmbeddingAdminController {
    private final EmbeddingAdminReadService service;
    private final EmbeddingAuthorizer authorizer;

    public EmbeddingAdminController(EmbeddingAdminReadService service, EmbeddingAuthorizer authorizer) {
        this.service = service;
        this.authorizer = authorizer;
    }

    @GetMapping("/profiles")
    public R<List<EmbeddingViews.Profile>> profiles() {
        authorizer.require(Permission.EMBEDDING_READ);
        return R.ok(service.profiles());
    }

    @GetMapping("/status")
    public R<EmbeddingViews.Status> status() {
        authorizer.require(Permission.EMBEDDING_READ);
        return R.ok(service.status());
    }

    @GetMapping("/rebuilds/{runId}")
    public R<EmbeddingViews.Run> run(@PathVariable UUID runId) {
        authorizer.require(Permission.EMBEDDING_READ);
        return R.ok(service.run(runId));
    }
}
