package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.DepartmentRecord;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.DepartmentService;
import com.zhishiku.kb.app.auth.service.InMemoryAuditStore;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 跨实体幂等命名空间。
 *
 * <p><b>为什么这个测试不能用 mock 的审计服务写</b>：要验证的失效模式发生在数据库的
 * 唯一约束上——{@code sys_identity_audit UNIQUE (operation_scope, request_id)}。
 * 用 {@code mock(IdentityAuditService.class)} 时"两个实体撞车"这件事根本不会发生，
 * 因为 mock 不认识幂等键，它只会照着 stub 回答，测试无论如何都会通过。
 *
 * <p>所以这里注入同一个按 {@code (scope, requestId)} 索引的内存假实现，
 * 并且让部门服务与空间服务<b>共用它</b>——两个实体在真实系统里写的是同一张表的
 * 同一个命名空间。若两个实体使用同一个裸操作域（例如都叫 {@code CREATE}），
 * 后到的那个会命中前者的审计行、被当成重试，并按前者的 targetId 返回
 * 另一个实体的数据：调用方拿到部门 ID 当作空间，而自己请求创建的空间
 * 从未被创建。这正是本测试要钉住的行为。
 */
class SpaceDepartmentAuditIsolationTest {

    private static final String SHARED_REQUEST = "req-shared-1";
    private static final long ACTOR = 7L;
    private static final long DEPT_ID = 11L;
    private static final long SPACE_ID = 22L;

    private InMemoryAuditStore audit;
    private IdentityDepartmentMapper departments;
    private SpaceMapper spaces;
    private SpaceAuthorizer authorizer;

    private DepartmentService departmentService;
    private SpaceWriteService spaceService;

    @BeforeEach
    void setUp() {
        audit = new InMemoryAuditStore();
        departments = mock(IdentityDepartmentMapper.class);
        spaces = mock(SpaceMapper.class);
        authorizer = mock(SpaceAuthorizer.class);

        departmentService = new DepartmentService(departments, audit);
        spaceService = new SpaceWriteService(spaces, authorizer, audit);
    }

    @Test
    @DisplayName("同一 request_id 下部门创建与空间创建各自独立执行，各留一条审计")
    void departmentAndSpaceCreationShareRequestIdWithoutColliding() {
        when(departments.findDepartmentByCode("platform")).thenReturn(null);
        when(departments.nextDepartmentId()).thenReturn(DEPT_ID);
        when(departments.insertDepartment(any())).thenReturn(1);

        when(authorizer.requireCreate()).thenReturn(ACTOR);
        when(spaces.findByCode("ops")).thenReturn(null);
        when(spaces.nextSpaceId()).thenReturn(SPACE_ID);
        when(spaces.insertSpace(any())).thenReturn(1);
        when(spaces.findRawById(SPACE_ID)).thenReturn(space(SPACE_ID));

        var department = departmentService.create("platform", "平台组", null, ACTOR, SHARED_REQUEST);
        var space = spaceService.create("ops", "运维", null, ACTOR, SHARED_REQUEST);

        // 各自返回自己的实体：空间创建绝不能返回部门的 ID
        assertThat(department.departmentId()).isEqualTo(DEPT_ID);
        assertThat(space.spaceId()).isEqualTo(SPACE_ID);
        assertThat(space.code()).isEqualTo("ops");

        // 两次创建都真的发生了，且各留一条审计事件
        assertThat(audit.events())
                .extracting(IdentityAuditRecord::getOperationScope)
                .containsExactlyInAnyOrder(AuditScope.DEPARTMENT_CREATE.name(),
                        AuditScope.SPACE_CREATE.name());
        assertThat(audit.events())
                .extracting(IdentityAuditRecord::getTargetId)
                .containsExactlyInAnyOrder(DEPT_ID, SPACE_ID);
    }

    @Test
    @DisplayName("两者都必须使用带实体前缀的操作域，不得共用裸动词")
    void bothSidesNamespaceTheirOperationScopeByEntity() {
        // 这条断言是上面那条的"为什么"：只要任一方退回裸操作域，
        // 两者的幂等键就落在同一个命名空间，撞车才成为可能
        assertThat(AuditScope.DEPARTMENT_CREATE.name()).isEqualTo("DEPARTMENT_CREATE");
        assertThat(AuditScope.SPACE_CREATE.name()).isEqualTo("SPACE_CREATE");
        assertThat(AuditScope.DEPARTMENT_CREATE.name())
                .isNotEqualTo(AuditScope.SPACE_CREATE.name());
    }

    @Test
    @DisplayName("同一 request_id 重放各自实体的创建请求，返回的仍是自己的实体")
    void replayingEachEntityReturnsItsOwnResult() {
        when(departments.findDepartmentByCode("platform")).thenReturn(null);
        when(departments.nextDepartmentId()).thenReturn(DEPT_ID);
        when(departments.insertDepartment(any())).thenReturn(1);
        when(departments.findDepartment(DEPT_ID)).thenReturn(department(DEPT_ID));

        when(authorizer.requireCreate()).thenReturn(ACTOR);
        when(spaces.findByCode("ops")).thenReturn(null);
        when(spaces.nextSpaceId()).thenReturn(SPACE_ID);
        when(spaces.insertSpace(any())).thenReturn(1);
        when(spaces.findRawById(SPACE_ID)).thenReturn(space(SPACE_ID));

        departmentService.create("platform", "平台组", null, ACTOR, SHARED_REQUEST);
        spaceService.create("ops", "运维", null, ACTOR, SHARED_REQUEST);

        // 重放：两边都命中自己去重记录，返回各自的实体，且不再新增审计
        var replayedDepartment = departmentService.create("platform", "平台组", null, ACTOR, SHARED_REQUEST);
        var replayedSpace = spaceService.create("ops", "运维", null, ACTOR, SHARED_REQUEST);

        assertThat(replayedDepartment.departmentId()).isEqualTo(DEPT_ID);
        assertThat(replayedSpace.spaceId()).isEqualTo(SPACE_ID);
        assertThat(audit.events()).hasSize(2);
    }

    @Test
    @DisplayName("契约：同一操作域下重放返回首个 targetId——这正是裸操作域会造成的冒充")
    void sharedNamespaceHandsBackTheFirstEntitysTargetId() {
        // 这条不经过服务层，直接钉住假实现与数据库唯一约束同构的那部分语义：
        // UNIQUE (operation_scope, request_id) 让同一操作域下的第二次写入
        // 变成"重试"，并把第一次的 targetId 交还给调用方。
        // 上面两条测试之所以成立，全靠两个实体不共用操作域——这里把这个前提本身
        // 变成断言，任何一方退回裸动词都会与这条契约组合出真实的数据冒充。
        var first = audit.success(AuditScope.DEPARTMENT_CREATE, SHARED_REQUEST, ACTOR,
                DEPT_ID, "create", "{}", "{}");
        var second = audit.success(AuditScope.DEPARTMENT_CREATE, SHARED_REQUEST, ACTOR,
                SPACE_ID, "create", "{}", "{}");

        assertThat(second.getTargetId())
                .as("同域重放不是新增事件，返回的是先到者，也就是另一个实体的 ID")
                .isEqualTo(DEPT_ID);
        assertThat(audit.events()).hasSize(1);
    }

    @Test
    @DisplayName("两实体各自的操作域不同，因此不会落入上面那条冒充路径")
    void distinctNamespacesKeepBothEntitiesOutOfTheCollisionPath() {
        assertThat(sharedNamespaces()).isEmpty();
    }

    /**
     * 若某请求标识在同一操作域下被两个实体同时写审计，这里会返回那个操作域。
     */
    private List<String> sharedNamespaces() {
        audit.success(AuditScope.DEPARTMENT_CREATE, SHARED_REQUEST, ACTOR, DEPT_ID, "create", "{}", "{}");
        audit.success(AuditScope.SPACE_CREATE, SHARED_REQUEST, ACTOR, SPACE_ID, "create", "{}", "{}");

        Map<String, List<Long>> targetsByRequest = new LinkedHashMap<>();
        List<String> collided = new ArrayList<>();
        for (IdentityAuditRecord event : audit.events()) {
            List<Long> targets = targetsByRequest.computeIfAbsent(
                    event.getOperationScope(), key -> new ArrayList<>());
            targets.add(event.getTargetId());
        }
        for (Map.Entry<String, List<Long>> entry : targetsByRequest.entrySet()) {
            if (entry.getValue().size() > 1) collided.add(entry.getKey());
        }
        return collided;
    }

    private static DepartmentRecord department(long id) {
        return new DepartmentRecord(id, null, "platform", "平台组", "/" + id + "/", true, 0L, null, null);
    }

    private static SpaceRecord space(long id) {
        SpaceRecord record = new SpaceRecord();
        record.setId(id);
        record.setCode("ops");
        record.setName("运维");
        record.setArchived(false);
        record.setVersion(0L);
        return record;
    }
}
