package com.zhishiku.kb.app.document.preview;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.common.api.R;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PreviewInfoController {
    private final PreviewInfoService service;

    public PreviewInfoController(PreviewInfoService service) {
        this.service = service;
    }

    @GetMapping("/api/documents/{documentId}/versions/{versionId}/preview-info")
    public ResponseEntity<R<PreviewInfo>> info(@PathVariable long documentId, @PathVariable long versionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .body(R.ok(service.info(StpUtil.getLoginIdAsLong(), documentId, versionId)));
    }
}
