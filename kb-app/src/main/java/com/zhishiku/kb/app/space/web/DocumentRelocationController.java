package com.zhishiku.kb.app.space.web;

import com.zhishiku.kb.app.space.service.DocumentRelocationService;
import com.zhishiku.kb.app.space.web.dto.DocumentRelocationRequest;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.DocumentRelocationResult;
import com.zhishiku.kb.common.security.IdentityException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档搬迁接口。
 *
 * <p>路径与文档例外授权接口平行（{@code .../space} 对 {@code .../acl}），因为两者的
 * 判定都是空间级的：搬迁的实体是文档，但它要求的是两个空间的管理资格，并且会操作
 * 同一张 {@code kb_document_acl}。
 *
 * <p>用 {@code PATCH} 而不是 {@code PUT}：请求只描述归属这一个字段的变更，不是文档
 * 资源的完整替换。
 */
@RestController
@RequestMapping("/api/admin/documents/{documentId}/space")
@Tag(name = "文档搬迁", description = "把文档迁入另一个知识空间；需要源空间与目标空间双侧 manage")
public class DocumentRelocationController {

    private final DocumentRelocationService relocations;

    public DocumentRelocationController(DocumentRelocationService relocations) {
        this.relocations = relocations;
    }

    /**
     * 把文档迁入另一个知识空间。
     *
     * <p>文档注释必须放在 {@code @Operation} 等注解<b>之前</b>：放在注解之后会被编译器
     * 当成普通注释，不进生成文档——那时接口文档里只剩注解里的字面文本，而"被清除的
     * 例外授权不可恢复"这类必须被调用方看见的后果就讲不全了。
     *
     * @param documentId 文档内部 ID；鉴权按服务端解析出的实际归属判定
     * @param request    声明的当前归属、目标空间与请求幂等标识
     * @param header     请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 搬迁回执：原归属、新归属、被清除的例外条数与摘要、目标空间中因密级
     * 当前看不到该文档的主体数
     */
    @PatchMapping
    @Operation(summary = "搬迁文档到另一个空间",
            description = "需要文档当前所属空间与目标空间双侧的 manage 资格；edit 不足。"
                    + "搬迁在同一事务内推进文档归属、同步已索引分块的空间归属、"
                    + "并清除该文档的全部文档级例外授权。"
                    + "**被清除的例外授权不可恢复**：它们的鉴权依据是文档当前所属空间的管理资格，"
                    + "搬迁后该依据已失效，要在新空间保留同等可见性必须由目标空间的管理员重新授出。"
                    + "搬迁不修改文档密级，请求夹带密级即返回 400。"
                    + "回执中的 clearanceBlockedPrincipalCount 不为 0 不代表搬迁失败，"
                    + "它只说明目标空间里有主体的密级低于该文档密级。"
                    + "请求必须声明 fromSpaceId 与 X-Request-Id（或请求体 requestId）；"
                    + "重复提交返回首次结果，不会再次清除例外")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "搬迁成功，或命中同一请求标识的重放"),
            @ApiResponse(responseCode = "400",
                    description = "缺少请求标识、源与目标相同，或请求夹带了密级变更"),
            @ApiResponse(responseCode = "403", description = "缺少源空间或目标空间的 manage 资格"),
            @ApiResponse(responseCode = "404", description = "文档或目标空间不存在、已删除"),
            @ApiResponse(responseCode = "409",
                    description = "声明的当前所属空间与实际不符、文档已在目标空间，或目标空间已归档")
    })
    public R<DocumentRelocationResult> relocate(
            @PathVariable @Positive long documentId,
            @Valid @RequestBody DocumentRelocationRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String header) {
        return R.ok(relocations.relocate(documentId, request.fromSpaceId(), request.toSpaceId(),
                request.securityLevel(), null, requestId(request.requestId(), header)));
    }

    /**
     * 请求体优先，请求头回退；两处都没有就是 400。
     *
     * <p>搬迁缺幂等标识的代价比别处重：一次超时重投会第二次清空例外，把目标空间管理员
     * 刚授出的授权一并清掉。所以这里不给默认值，也不生成一个服务端标识。
     */
    private static String requestId(String body, String header) {
        String value = body == null || body.isBlank() ? header : body;
        if (value == null || value.isBlank()) {
            throw IdentityException.badRequest("缺少请求标识 requestId");
        }
        return value;
    }
}
