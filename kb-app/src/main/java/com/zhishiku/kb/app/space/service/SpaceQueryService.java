package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.auth.service.PermissionService.PrincipalHandle;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PermissionScope;
import com.zhishiku.kb.common.security.SpaceSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 空间查询。
 *
 * <p>每个空间返回两个<b>互相独立</b>的资格标记：{@code viewable}（能否读内容）
 * 与 {@code manageable}（能否改授权名单）。它们分别由 view 与 manage 两次
 * 权限解析得出，不能互相推断——manage 不蕴含 view。客户端据此决定按钮是否
 * 可用，服务端仍在每个写入口重新判定（界面开关不是鉴权）。
 */
@Service
public class SpaceQueryService {

    private final SpaceMapper mapper;
    private final PermissionService permissionService;
    private final SpaceAuthorizer authorizer;

    public SpaceQueryService(SpaceMapper mapper, PermissionService permissionService,
                             SpaceAuthorizer authorizer) {
        this.mapper = mapper;
        this.permissionService = permissionService;
        this.authorizer = authorizer;
    }

    /**
     * 列出调用者可见或有管理资格的空间；已归档空间只在其可管理时出现。
     */
    @Transactional(readOnly = true)
    public List<SpaceSummary> list(long userId) {
        ScopePair pair = resolve(userId);
        return mapper.listVisible(pair.viewSpaceIds(), pair.manageSpaceIds(), pair.globalManager())
                .stream().map(SpaceQueryService::summary).toList();
    }

    /**
     * 查询单个空间摘要；既不可见也不可管理时返回 404，不泄露其存在性。
     */
    @Transactional(readOnly = true)
    public SpaceSummary get(long userId, long spaceId) {
        ScopePair pair = resolve(userId);
        SpaceRecord record = mapper.findById(spaceId, pair.viewSpaceIds(),
                pair.manageSpaceIds(), pair.globalManager());
        if (record == null) throw IdentityException.notFound("空间不存在或当前用户无权访问");
        return summary(record);
    }

    /**
     * 只校验当前管理资格；归档空间可管理，不以阅读资格代替管理资格。
     */
    @Transactional(readOnly = true, timeout = 5)
    public void requireManaged(PrincipalHandle handle, long spaceId) {
        handle.requireEnabled();
        boolean global = handle.functionalPermissions().contains(Permission.SPACE_MANAGE_ALL.code());
        List<Long> managed = global ? List.of()
                : permissionService.resolve(handle, PermissionService.PERM_MANAGE).visibleSpaceIdList();
        if ((!global && !managed.contains(spaceId))
                || mapper.findById(spaceId, List.of(), managed, global) == null) throw QueryException.missing();
    }

    /**
     * 分别按 view 与 manage 解析权限；主体只解析一次。
     *
     * <p>space:manage-all 可绕过 ACL，避免无授权空间无人可管理；登录和启用状态由
     * SpaceAuthorizer.requireEnabledUser 在控制器进入本服务前校验。其他管理权限不触发该分支。
     */
    private ScopePair resolve(long userId) {
        if (authorizer.has(Permission.SPACE_MANAGE_ALL)) {
            return new ScopePair(List.of(), List.of(), true);
        }
        var handle = permissionService.principalOf(userId);
        PermissionScope view = permissionService.resolve(handle, PermissionService.PERM_VIEW);
        PermissionScope manage = permissionService.resolve(handle, PermissionService.PERM_MANAGE);
        return new ScopePair(view.visibleSpaceIdList(), manage.visibleSpaceIdList(), false);
    }

    /**
     * 一次解析出的访问范围，避免同一用户重复查询权限。
     *
     * @param viewSpaceIds   可查看的空间 ID
     * @param manageSpaceIds 可管理的空间 ID
     * @param globalManager  是否为全局管理者；为真时忽略前两项
     */
    private record ScopePair(List<Long> viewSpaceIds, List<Long> manageSpaceIds, boolean globalManager) {
    }

    private static SpaceSummary summary(SpaceRecord record) {
        return new SpaceSummary(record.getId(), record.getCode(), record.getName(),
                record.getDescription(), Boolean.TRUE.equals(record.getArchived()),
                record.getVersion() == null ? 0L : record.getVersion(),
                Boolean.TRUE.equals(record.getViewable()),
                Boolean.TRUE.equals(record.getManageable()));
    }
}
