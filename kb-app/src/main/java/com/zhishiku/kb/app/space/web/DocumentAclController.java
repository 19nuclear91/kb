package com.zhishiku.kb.app.space.web;

import com.zhishiku.kb.app.space.service.DocumentAclService;
import com.zhishiku.kb.app.space.web.dto.SpaceAclGrantRequest;
import com.zhishiku.kb.app.space.web.dto.SpaceAclRevokeRequest;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.SpaceAclGrantResult;
import com.zhishiku.kb.common.security.SpaceAclSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文档级例外授权接口。
 *
 * <p>鉴权依据是<b>文档当前所属空间</b>的管理资格，不接受请求里声称的空间。
 * 例外只能扩权：它把空间外的个别主体额外放进来，无法用它收紧空间已有的授权。
 */
@RestController
@RequestMapping("/api/admin/documents/{documentId}/acl")
@Tag(name = "文档例外授权", description = "为个别文档额外授予空间外主体访问权；只能扩权")
public class DocumentAclController {

    private final DocumentAclService acls;

    public DocumentAclController(DocumentAclService acls) {
        this.acls = acls;
    }

    @GetMapping
    @Operation(summary = "列出文档例外授权",
            description = "需要该文档所属空间的 manage")
    /**
     * 列出文档额外授权条目。
     *
     * @param documentId 文档内部 ID；鉴权按文档当前所属空间判定
     * @return 该文档的例外授权列表
     */
    public R<List<SpaceAclSummary>> list(@PathVariable @Positive long documentId) {
        return R.ok(acls.list(documentId));
    }

    @PostMapping
    @Operation(summary = "授予文档例外授权",
            description = "需要该文档所属空间的 manage。可授予 view、download、edit、comment；"
                    + "comment 只为该文档增加发表评论资格，不隐含 view；review 与 manage 不能用于文档级例外。例外不修改密级，"
                    + "响应会回执因密级而当前不生效的主体数量；重复授予是幂等的")
    /**
     * 为文档所属空间之外的主体增加一条文档级访问例外。
     *
     * @param documentId 文档内部 ID；不会从请求体读取或信任空间 ID
     * @param request 被授权主体、权限位和请求幂等标识
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 授权条目、幂等命中标记以及因密级暂时无法生效的主体数量
     */
    public R<SpaceAclGrantResult> grant(@PathVariable @Positive long documentId,
                                        @Valid @RequestBody SpaceAclGrantRequest request,
                                        @RequestHeader(value = "X-Request-Id", required = false) String header) {
        return R.ok(acls.grant(documentId, request.principalType(), request.principalId(),
                request.permission(), null, requestId(request.requestId(), header)));
    }

    @DeleteMapping
    @Operation(summary = "撤销文档例外授权",
            description = "需要该文档所属空间的 manage。撤销不存在的例外是幂等的，但仍留下审计事件")
    /**
     * 撤销一条文档级例外授权。
     *
     * @param documentId 文档内部 ID
     * @param request 被撤销主体、权限位和请求幂等标识
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 空成功响应；目标授权不存在时仍按幂等成功处理
     */
    public R<Void> revoke(@PathVariable @Positive long documentId,
                          @Valid @RequestBody SpaceAclRevokeRequest request,
                          @RequestHeader(value = "X-Request-Id", required = false) String header) {
        acls.revoke(documentId, request.principalType(), request.principalId(), request.permission(),
                null, requestId(request.requestId(), header));
        return R.ok();
    }

    private static String requestId(String body, String header) {
        String value = body == null || body.isBlank() ? header : body;
        if (value == null || value.isBlank()) {
            throw IdentityException.badRequest("缺少请求标识 requestId");
        }
        return value;
    }
}
