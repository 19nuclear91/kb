package com.zhishiku.kb.app.space.web;

import com.zhishiku.kb.app.query.AdminQueryEndpoint;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.app.space.model.UploadSpaceCandidate;
import com.zhishiku.kb.app.space.service.UploadSpaceQueryService;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端可上传空间入口；标记仅复用查询错误处理，不要求管理权限。
 */
@AdminQueryEndpoint
@RestController
@Tag(name = "用户资源能力")
public class UploadSpaceController {
    private final UploadSpaceQueryService service;

    public UploadSpaceController(UploadSpaceQueryService service) {
        this.service = service;
    }

    /**
     * 列出当前用户真正持有空间 edit 的候选。
     *
     * @param params 仅允许 limit 和 cursor
     * @return 最小候选分页
     */
    @GetMapping("/api/auth/upload-spaces")
    @Operation(summary = "查询可上传空间", description = "仅按当前空间 edit；默认 20，上限 100，不返回正文或全局总数")
    public R<QueryPage<UploadSpaceCandidate>> list(@RequestParam MultiValueMap<String, String> params) {
        return R.ok(service.list(params));
    }
}
