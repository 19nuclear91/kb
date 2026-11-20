package com.zhishiku.kb.app.document.metadata.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.metadata.support.MetadataInput;
import com.zhishiku.kb.app.document.suggestion.SuggestionJob;
import com.zhishiku.kb.app.document.suggestion.SuggestionRequests;
import com.zhishiku.kb.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents/{id}/metadata-suggestions")
public class MetadataSuggestionController {
    private final SuggestionRequests requests;

    public MetadataSuggestionController(SuggestionRequests requests) {
        this.requests = requests;
    }

    @PostMapping
    public ResponseEntity<R<SuggestionJob.View>> create(@PathVariable String id, @RequestHeader("X-Request-Id") String key,
                                                       HttpServletRequest request) {
        var body = MetadataBody.read(request, MetadataInput.QUERY_LIMIT);
        var fields = Set.of("sourceVersionId", "expectedMetadataVersion");
        MetadataInput.fields(body, fields, fields);
        var view = requests.create(StpUtil.getTokenValue(), MetadataInput.id(id), MetadataInput.nullableId(body.get("sourceVersionId")),
                MetadataInput.number(body.get("expectedMetadataVersion"), 0, Long.MAX_VALUE), uuid(key));
        return ResponseEntity.status("RUNNING".equals(view.status()) ? 202 : 200)
                .header("Cache-Control", "no-store").body(R.ok(view));
    }

    @GetMapping("/{key}")
    public R<SuggestionJob.View> get(@PathVariable String id, @PathVariable String key) {
        return R.ok(requests.get(StpUtil.getTokenValue(), MetadataInput.id(id), uuid(key)));
    }

    @PostMapping("/{key}/cancel")
    public R<SuggestionJob.View> cancel(@PathVariable String id, @PathVariable String key) {
        return R.ok(requests.cancel(StpUtil.getTokenValue(), MetadataInput.id(id), uuid(key)));
    }

    public static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) {
                return id;
            }
        } catch (IllegalArgumentException ignored) {
            // 统一返回安全输入错误。
        }
        throw new MetadataException(MetadataException.Reason.INVALID_INPUT);
    }
}
