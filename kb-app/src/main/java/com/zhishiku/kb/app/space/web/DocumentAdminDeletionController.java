package com.zhishiku.kb.app.space.web;

import com.zhishiku.kb.app.space.model.DocumentAdminDeleteResult;
import com.zhishiku.kb.app.space.service.DocumentAdminDeletionService;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端整文档删除接口。
 *
 * <p>与文档搬迁、文档例外授权平行：判定依据都是文档<b>当前所属空间</b>的管理资格，
 * 不接受请求里声称的空间。空间 manage 足以删除，无需 edit；已归档空间中的文档仍可删除。
 *
 * <p>删除效果与用户端 {@code DELETE /api/documents/{id}} 完全一致，差别只在资格判定
 * 与幂等/审计包装：软删除整份文档及全部版本、取消未完成解析任务与审核轮次，提交后尽力
 * 清理原件，不提供恢复。
 */
@RestController
@RequestMapping("/api/admin/documents/{documentId}")
@Tag(name = "文档管理端删除", description = "按当前所属空间 manage 删除整份文档；可审计、幂等、不可恢复")
public class DocumentAdminDeletionController {

    private final DocumentAdminDeletionService deletions;

    public DocumentAdminDeletionController(DocumentAdminDeletionService deletions) {
        this.deletions = deletions;
    }

    /**
     * 删除整份文档及其全部版本。
     *
     * <p>文档注释必须放在 {@code @Operation} 等注解<b>之前</b>，否则会被编译器当成普通
     * 注释而不进生成文档。
     *
     * @param documentId 文档内部 ID；鉴权按服务端解析出的实际归属判定
     * @param requestId  请求头 {@code X-Request-Id}，必填；同一标识重复提交返回首次回执
     * @return 只含文档 ID、所属空间 ID 与 replayed 的回执，不含标题、文件名或正文
     */
    @DeleteMapping
    @Operation(summary = "删除整份文档",
            description = "需要文档当前所属空间的 manage 或 space:manage-all；edit、view、"
                    + "文档例外授权与上传者身份都不能替代。已归档空间中的文档仍可删除。"
                    + "删除整份文档及其全部版本，取消未完成解析任务与审核轮次，提交后尽力清理全部版本原件，"
                    + "**不可恢复**。文档不存在、已删除或操作者无权管理其所属空间统一返回 404；"
                    + "资格判定后归属发生变化返回 409。请求必须携带 X-Request-Id，"
                    + "同一标识重复提交返回首次回执且 replayed 为真，不再写入。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "删除成功，或命中同一请求标识的重放"),
            @ApiResponse(responseCode = "400", description = "缺少 X-Request-Id，或文档 ID 不是正整数"),
            @ApiResponse(responseCode = "404", description = "文档不存在、已删除或无权管理其所属空间"),
            @ApiResponse(responseCode = "409", description = "资格判定后文档归属发生变化")
    })
    public R<DocumentAdminDeleteResult> delete(
            @PathVariable @Positive long documentId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return R.ok(deletions.delete(documentId, requireRequestId(requestId)));
    }

    private static String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw IdentityException.badRequest("缺少请求标识 X-Request-Id");
        }
        return requestId;
    }
}
