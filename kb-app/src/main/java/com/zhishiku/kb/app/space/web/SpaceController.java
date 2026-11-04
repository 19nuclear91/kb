package com.zhishiku.kb.app.space.web;

import com.zhishiku.kb.app.space.model.SpaceAclBatchGrantResult;
import com.zhishiku.kb.app.space.model.SpaceAclBatchRevokeResult;
import com.zhishiku.kb.app.space.service.SpaceAclService;
import com.zhishiku.kb.app.space.service.SpaceAuthorizer;
import com.zhishiku.kb.app.space.service.SpaceQueryService;
import com.zhishiku.kb.app.space.service.SpaceWriteService;
import com.zhishiku.kb.app.space.web.dto.SpaceAclGrantRequest;
import com.zhishiku.kb.app.space.web.dto.SpaceAclBatchGrantRequest;
import com.zhishiku.kb.app.space.web.dto.SpaceAclBatchRevokeRequest;
import com.zhishiku.kb.app.space.web.dto.SpaceAclRevokeRequest;
import com.zhishiku.kb.app.space.web.dto.SpaceArchiveRequest;
import com.zhishiku.kb.app.space.web.dto.SpaceCreateRequest;
import com.zhishiku.kb.app.space.web.dto.SpaceDeleteRequest;
import com.zhishiku.kb.app.space.web.dto.SpaceUpdateRequest;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.SpaceAclGrantResult;
import com.zhishiku.kb.common.security.SpaceAclSummary;
import com.zhishiku.kb.common.security.SpaceSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 空间管理接口。
 *
 * <p>错误语义沿用 {@link IdentityException}：403 无权、404 不存在或不可见、
 * 409 冲突（编码重复、仍有文档、版本过期）。幂等由 {@code X-Request-Id}
 * 承担，缺失时返回 400 而不是静默放弃去重。
 */
@RestController
@RequestMapping("/api/admin/spaces")
@Tag(name = "空间管理", description = "空间生命周期接口；读接口还需登录，写接口按空间管理资格判定")
public class SpaceController {

    private final SpaceQueryService queries;
    private final SpaceWriteService writes;
    private final SpaceAclService acls;
    private final SpaceAuthorizer authorizer;

    public SpaceController(SpaceQueryService queries, SpaceWriteService writes,
                           SpaceAclService acls, SpaceAuthorizer authorizer) {
        this.queries = queries;
        this.writes = writes;
        this.acls = acls;
        this.authorizer = authorizer;
    }

    @GetMapping
    @Operation(summary = "列出当前用户有资格的空间",
            description = "每个空间分别给出 viewable 与 manageable 标记；"
                    + "已归档空间仅在可管理时出现，且 viewable=false")
    /**
     * 返回当前登录用户可查看或管理的空间摘要。
     *
     * @return 空间摘要列表；每项的 {@code viewable} 与 {@code manageable}
     *         均按当前登录用户实时计算
     */
    public R<List<SpaceSummary>> list() {
        long userId = authorizer.requireEnabledUser();
        return R.ok(queries.list(userId));
    }

    @GetMapping("/{spaceId}")
    @Operation(summary = "查询空间摘要")
    /**
     * 查询一个空间在当前登录用户视角下的摘要。
     *
     * @param spaceId 空间内部 ID
     * @return 空间元数据、归档状态、版本号以及当前用户的可见和可管理标记
     */
    public R<SpaceSummary> get(@PathVariable @Positive long spaceId) {
        long userId = authorizer.requireEnabledUser();
        return R.ok(queries.get(userId, spaceId));
    }

    @PostMapping
    @Operation(summary = "创建空间",
            description = "仅全局身份管理员可创建；创建成功即在同一事务内为创建者授予该空间的 manage")
    /**
     * 创建空间，并在同一事务中给创建者授予 {@code manage} 权限。
     *
     * @param request 空间编码、名称、描述和请求幂等标识
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 创建后的空间摘要
     */
    public R<SpaceSummary> create(@Valid @RequestBody SpaceCreateRequest request,
                                  @RequestHeader(value = "X-Request-Id", required = false) String header) {
        return R.ok(writes.create(request.code(), request.name(), request.description(),
                null, requestId(request.requestId(), header)));
    }

    @PatchMapping("/{spaceId}")
    @Operation(summary = "更新空间元数据", description = "需要该空间的 manage；version 过期返回 409")
    /**
     * 按乐观锁版本更新空间名称和描述。
     *
     * @param spaceId 空间内部 ID
     * @param request 新名称、描述、客户端读取到的版本和请求幂等标识
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 更新后的空间摘要
     */
    public R<SpaceSummary> update(@PathVariable @Positive long spaceId,
                                  @Valid @RequestBody SpaceUpdateRequest request,
                                  @RequestHeader(value = "X-Request-Id", required = false) String header) {
        return R.ok(writes.updateMetadata(spaceId, request.name(), request.description(),
                request.version(), null, requestId(request.requestId(), header)));
    }

    @PostMapping("/{spaceId}/archive")
    @Operation(summary = "归档空间",
            description = "归档后空间退出读权限位的可见范围，其文档不再出现在检索、列表、详情与下载中；"
                    + "manage 不受影响，因此被委派的管理员仍可取消归档")
    /**
     * 归档空间，使其退出读取范围但保留管理可达性。
     *
     * @param spaceId 空间内部 ID
     * @param request 客户端读取到的空间版本和请求幂等标识
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 归档后的空间摘要，返回的 {@code archived} 为 {@code true}
     */
    public R<SpaceSummary> archive(@PathVariable @Positive long spaceId,
                                   @Valid @RequestBody SpaceArchiveRequest request,
                                   @RequestHeader(value = "X-Request-Id", required = false) String header) {
        return R.ok(writes.archive(spaceId, request.version(), null,
                requestId(request.requestId(), header)));
    }

    @PostMapping("/{spaceId}/unarchive")
    @Operation(summary = "取消归档", description = "按原有 ACL 与密级规则恢复可见，不需重建授权")
    /**
     * 取消空间归档，恢复原有 ACL 和密级规则下的可见性。
     *
     * @param spaceId 空间内部 ID
     * @param request 客户端读取到的空间版本和请求幂等标识
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 取消归档后的空间摘要，返回的 {@code archived} 为 {@code false}
     */
    public R<SpaceSummary> unarchive(@PathVariable @Positive long spaceId,
                                     @Valid @RequestBody SpaceArchiveRequest request,
                                     @RequestHeader(value = "X-Request-Id", required = false) String header) {
        return R.ok(writes.unarchive(spaceId, request.version(), null,
                requestId(request.requestId(), header)));
    }

    @DeleteMapping("/{spaceId}")
    @Operation(summary = "删除空间",
            description = "仅允许删除没有文档的空间；仍有文档时返回 409 并提示改用归档。"
                    + "空空间删除时其授权记录随外键级联移除")
    /**
     * 删除空空间及其授权记录。
     *
     * @param spaceId 空间内部 ID
     * @param request 可选请求体，包含请求幂等标识；DELETE 无请求体时可传请求头
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 空成功响应；空间仍有关联文档时抛出冲突错误
     */
    public R<Void> delete(@PathVariable @Positive long spaceId,
                          @RequestBody(required = false) SpaceDeleteRequest request,
                          @RequestHeader(value = "X-Request-Id", required = false) String header) {
        String bodyId = request == null ? null : request.requestId();
        writes.delete(spaceId, null, requestId(bodyId, header));
        return R.ok();
    }

    @GetMapping("/{spaceId}/acl")
    @Operation(summary = "列出空间授权",
            description = "需要该空间的 manage。返回每条授权的主体类型、主体、权限位与主体名")
    /**
     * 列出空间当前的直接授权条目。
     *
     * @param spaceId 空间内部 ID
     * @return 授权条目列表，包含主体类型、主体 ID、权限位和主体显示名
     */
    public R<List<SpaceAclSummary>> listAcl(@PathVariable @Positive long spaceId) {
        return R.ok(acls.list(spaceId));
    }

    @PostMapping("/{spaceId}/acl")
    @Operation(summary = "授予空间访问权",
            description = "需要该空间的 manage。可授予 view、download、edit、review、comment；"
                    + "review 只表示审核人池成员资格，comment 只表示发表评论资格，两者都不隐含 view、download、edit 或足够密级。"
                    + "manage 的授予需要 space:grant-manage。"
                    + "授权不修改任何主体的密级，因此响应会回执因密级而当前不生效的主体数量，"
                    + "并且重复授予同一条授权是幂等的")
    /**
     * 为用户、角色或部门授予空间权限。
     *
     * @param spaceId 空间内部 ID
     * @param request 被授权主体、权限位和请求幂等标识
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 授权条目、是否已经存在以及因密级暂时无法生效的主体数量
     */
    public R<SpaceAclGrantResult> grantAcl(@PathVariable @Positive long spaceId,
                                           @Valid @RequestBody SpaceAclGrantRequest request,
                                           @RequestHeader(value = "X-Request-Id", required = false) String header) {
        return R.ok(acls.grant(spaceId, request.principalType(), request.principalId(),
                request.permission(), null, requestId(request.requestId(), header)));
    }

    @PostMapping("/{spaceId}/acl/batch")
    @Operation(summary = "批量授予空间访问权",
            description = "同一事务内授予最多 50 个主体与 6 个权限位的笛卡尔积；全部校验通过才写入。"
                    + "包含 manage 时整批要求 space:grant-manage，否则要求空间 manage 或 space:manage-all。"
                    + "必须携带 X-Request-Id，重放返回首次逐条回执；授权不修改主体密级。")
    public R<SpaceAclBatchGrantResult> grantAclBatch(@PathVariable @Positive long spaceId,
                                                     @RequestBody SpaceAclBatchGrantRequest request,
                                                     @RequestHeader(value = "X-Request-Id", required = false) String header) {
        return R.ok(acls.grantBatch(spaceId, request.principals(), request.permissions(), requestId(null, header)));
    }

    @PostMapping("/{spaceId}/acl/batch-revoke")
    @Operation(summary = "批量撤销空间访问权",
            description = "同一事务内仅撤销显式列出的 1 至 300 条授权，主体与权限位组合不得重复。"
                    + "包含 manage 时整批要求 space:grant-manage，否则要求空间 manage 或 space:manage-all。"
                    + "请求标识只取 X-Request-Id，相同标识与内容重放首次回执，不同内容返回 409。"
                    + "撤销不存在条目视为成功；允许撤销全部直接 manage。")
    public R<SpaceAclBatchRevokeResult> revokeAclBatch(@PathVariable @Positive long spaceId,
                                                      @RequestBody SpaceAclBatchRevokeRequest request,
                                                      @RequestHeader(value = "X-Request-Id", required = false) String header) {
        return R.ok(acls.revokeBatch(spaceId, request.items(), requestId(null, header)));
    }

    @DeleteMapping("/{spaceId}/acl")
    @Operation(summary = "撤销空间访问权",
            description = "需要该空间的 manage；撤销 manage 需要 space:grant-manage。"
                    + "撤销不存在的授权是幂等的，不返回失败，但仍留下审计事件")
    /**
     * 撤销空间上的一条直接授权。
     *
     * @param spaceId 空间内部 ID
     * @param request 被撤销主体、权限位和请求幂等标识
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 空成功响应；授权不存在时也按幂等成功处理
     */
    public R<Void> revokeAcl(@PathVariable @Positive long spaceId,
                             @Valid @RequestBody SpaceAclRevokeRequest request,
                             @RequestHeader(value = "X-Request-Id", required = false) String header) {
        acls.revoke(spaceId, request.principalType(), request.principalId(), request.permission(),
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
