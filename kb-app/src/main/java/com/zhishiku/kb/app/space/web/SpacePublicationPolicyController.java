package com.zhishiku.kb.app.space.web;

import com.zhishiku.kb.app.query.CommandInput;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.app.space.model.SpacePublicationPolicy;
import com.zhishiku.kb.app.space.service.SpaceAuthorizer;
import com.zhishiku.kb.app.space.service.SpacePublicationPolicyService;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.review.PublicationPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/spaces/{spaceId}/publication-policy")
@Tag(name = "空间发布策略")
public class SpacePublicationPolicyController {
    private final SpacePublicationPolicyService service;
    private final SpaceAuthorizer authorizer;

    public SpacePublicationPolicyController(SpacePublicationPolicyService service, SpaceAuthorizer authorizer) {
        this.service = service;
        this.authorizer = authorizer;
    }

    @GetMapping
    @Operation(summary = "读取空间发布策略", description = "要求当前 manage 或 space:manage-all，不授予正文或审核资格")
    public R<SpacePublicationPolicy> get(@PathVariable String spaceId, HttpServletRequest request) throws IOException {
        authorizer.requireEnabledUser();
        CommandInput.noPayload(request);
        return R.ok(service.get(QueryParameters.positiveValue(spaceId)));
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "修改空间发布策略", description = "输入 policy、expectedVersion 和 X-Request-Id；仅影响之后插入的版本")
    public R<SpacePublicationPolicy> update(@PathVariable String spaceId, HttpServletRequest request) {
        authorizer.requireEnabledUser();
        CommandInput.noQuery(request);
        long id = QueryParameters.positiveValue(spaceId);
        String requestId = CommandInput.requestId(request);
        try {
            var body = CommandInput.object(request.getInputStream(), Set.of("policy", "expectedVersion"));
            var policy = PublicationPolicy.valueOf(CommandInput.text(body.get("policy")));
            return R.ok(service.update(id, policy, CommandInput.nonnegative(body.get("expectedVersion")), requestId));
        } catch (IOException | IllegalArgumentException invalid) {
            throw QueryException.invalid();
        }
    }
}
