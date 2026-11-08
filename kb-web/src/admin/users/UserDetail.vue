<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import { ElButton, ElDescriptions, ElDescriptionsItem, ElSpace, ElTag } from 'element-plus';
import KbDrawer from '../../ui/KbDrawer.vue';
import KbAlert from '../../ui/KbAlert.vue';
import Feedback from '../../ui/Feedback.vue';
import { useSession } from '../../SessionProvider';
import type { QueryPage } from '../../types';
import { perm, useAdminAccess } from '../access';
import { adminPaths } from '../api';
import { useAdminResource, useCursorList } from '../hooks';
import type { AdminUser, RoleSummary } from '../types';
import Note from '../Note.vue';
import UserLifecyclePanel from '../lifecycle/UserLifecyclePanel.vue';
import { departmentText, missingDepartments, type Departments } from './labels';
import MoveUserDialog from './MoveUserDialog.vue';
import EnabledUserDialog from './EnabledUserDialog.vue';
import PasswordDialog from './PasswordDialog.vue';
import ClearanceDialog from './ClearanceDialog.vue';
import AssignRoleDialog from './AssignRoleDialog.vue';
import RevokeRoleDialog from './RevokeRoleDialog.vue';

const props = defineProps<{ userId: string; departments: Departments }>();
const emit = defineEmits<{ close: []; changed: [] }>();
type Dialog = 'department' | 'enabled' | 'password' | 'clearance' | 'assign' | { revoke: RoleSummary };
const auth = useSession(), access = useAdminAccess();
const detail = useAdminResource<AdminUser>(signal => auth.session.json(adminPaths.user(props.userId), { signal }), () => `user:${props.userId}`);
const roles = useCursorList<RoleSummary>((cursor, signal) => auth.session.json<QueryPage<RoleSummary>>(adminPaths.userRoles(props.userId, cursor), { signal }), () => `user-roles:${props.userId}`);
const dialog = shallowRef<Dialog | null>(null), receipt = ref(''), lifecycleRevision = ref(0);
function refreshDetail() {
  detail.reload(); lifecycleRevision.value++;
}
function refreshRoles() {
  roles.reload(); lifecycleRevision.value++;
}
function done(text: string, scope: 'detail' | 'roles') {
  if (dialog.value === 'department') {
    access.check(true);
  }
  dialog.value = null; receipt.value = text;
  if (scope === 'roles') {
    refreshRoles(); access.check(true);
  } else {
    refreshDetail(); emit('changed');
  }
}
const user = computed(() => detail.data), self = computed(() => user.value?.userId === auth.state.me?.user.userId);
const deptRead = computed(() => access.has(perm.deptRead)), roleRead = computed(() => access.has(perm.roleRead)), roleAssign = computed(() => access.has(perm.roleAssign));
const allowed = computed(() => (dialog.value === 'department' ? access.has(perm.userWrite) && deptRead.value : dialog.value === 'enabled' ? access.has(perm.userWrite)
  : dialog.value === 'password' ? access.has(perm.password) : dialog.value === 'clearance' ? access.has(perm.clearance) && !self.value
    : dialog.value === 'assign' ? roleAssign.value && roleRead.value : dialog.value ? roleAssign.value : true) && (!dialog.value || !!user.value));
watch(allowed, value => {
  if (!value) {
    dialog.value = null;
  }
});
function close() {
  if (!dialog.value) {
    emit('close');
  }
}
function lifecycleChanged() {
  detail.reload(); emit('changed');
}
</script>

<template>
  <KbDrawer title="账号详情" open :close-on-click-modal="!dialog" @close="close">
    <KbAlert v-if="receipt" type="success" show-icon :title="receipt" style="margin-bottom: 16px" />
    <KbAlert v-if="detail.status === 404" type="warning" show-icon title="该账号不存在或当前不可访问。"><template #action><ElButton @click="emit('close')">关闭详情</ElButton></template></KbAlert>
    <Feedback v-else-if="!user" :loading="detail.loading" :error="detail.error" :retry="detail.reload" />
    <template v-else>
      <ElDescriptions :column="1" size="small"><ElDescriptionsItem label="用户名">{{ user.username }}</ElDescriptionsItem><ElDescriptionsItem label="显示名">{{ user.displayName }}</ElDescriptionsItem><ElDescriptionsItem label="主部门">{{ departmentText(departments, user.departmentId) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="密级">密级 {{ user.clearanceLevel }}</ElDescriptionsItem><ElDescriptionsItem label="状态"><ElTag :type="user.enabled ? 'success' : 'info'">{{ user.enabled ? '启用' : '已停用' }}</ElTag></ElDescriptionsItem></ElDescriptions>
      <KbAlert v-if="detail.error" type="error" :title="detail.error" style="margin-top: 12px"><template #action><ElButton @click="detail.reload">重新读取</ElButton></template></KbAlert>
      <h3 class="admin-subtitle">账号操作</h3><ElSpace wrap>
        <ElButton v-if="access.has(perm.userWrite)" :disabled="!deptRead || detail.loading" @click="dialog = 'department'">调整主部门</ElButton>
        <ElButton v-if="access.has(perm.userWrite)" :type="user.enabled ? 'danger' : undefined" :plain="user.enabled" :disabled="detail.loading" @click="dialog = 'enabled'">{{ user.enabled ? '停用账号' : '启用账号' }}</ElButton>
        <ElButton v-if="access.has(perm.password)" @click="dialog = 'password'">重置密码</ElButton><ElButton v-if="access.has(perm.clearance)" :disabled="self || detail.loading" @click="dialog = 'clearance'">调整密级</ElButton>
      </ElSpace>
      <Note v-if="access.has(perm.userWrite) && !deptRead">{{ missingDepartments }}</Note><Note v-if="access.has(perm.clearance) && self">不能调整本人的密级。</Note>
      <h3 class="admin-subtitle">绑定角色</h3>
      <ul v-if="roles.items.length" class="admin-list"><li v-for="role in roles.items" :key="role.roleId"><span>{{ role.name }} <span class="muted">{{ role.code }}</span></span>
        <ElButton v-if="roleAssign" size="small" :aria-label="`解除角色 ${role.code}`" @click="dialog = { revoke: role }">解除绑定</ElButton></li></ul>
      <Feedback v-else-if="roles.loading || roles.error" :loading="roles.loading" :error="roles.error" :retry="roles.reload" /><p v-else class="muted">尚未绑定角色。</p>
      <KbAlert v-if="roles.items.length > 0 && roles.error" type="error" :title="roles.error" style="margin-top: 8px"><template #action><ElButton @click="roles.more">重试读取</ElButton></template></KbAlert>
      <ElButton v-if="roles.items.length > 0 && roles.hasMore && !roles.error" size="small" :loading="roles.loading" @click="roles.more">更多绑定角色</ElButton>
      <div v-if="roleAssign" style="margin-top: 12px"><ElButton :disabled="!roleRead || roles.loading" @click="dialog = 'assign'">分配角色</ElButton><Note v-if="!roleRead">缺少 identity:role:read，无法列出完整角色候选；仍可解除已读取的绑定，不提供手工输入角色 ID。</Note></div>
      <Note>绑定列表只显示角色摘要；角色包含的功能权限请在角色管理中查看。</Note>
      <UserLifecyclePanel :user-id="userId" :refresh-revision="lifecycleRevision" @changed="lifecycleChanged" />
    </template>
    <MoveUserDialog v-if="user && dialog === 'department' && access.has(perm.userWrite) && deptRead" :user="user" :departments="departments" :refreshing="detail.loading" :on-refresh="refreshDetail" @close="dialog = null" @done="done('主部门已调整。', 'detail')" />
    <EnabledUserDialog v-if="user && dialog === 'enabled' && access.has(perm.userWrite)" :user="user" :refreshing="detail.loading" :on-refresh="refreshDetail" @close="dialog = null" @done="done($event ? '账号已启用。' : '账号已停用。', 'detail')" />
    <PasswordDialog v-if="user && dialog === 'password' && access.has(perm.password)" :user="user" :on-refresh="refreshDetail" @close="dialog = null" @done="done('密码已重置，该账号下次登录需修改密码。', 'detail')" />
    <ClearanceDialog v-if="user && dialog === 'clearance' && access.has(perm.clearance) && !self" :user="user" :refreshing="detail.loading" :on-refresh="refreshDetail" @close="dialog = null" @done="done('密级已调整。', 'detail')" />
    <AssignRoleDialog v-if="user && dialog === 'assign' && roleAssign && roleRead" :user="user" :bound="roles.items" :on-refresh="refreshRoles" @close="dialog = null" @done="done('角色已分配。', 'roles')" />
    <RevokeRoleDialog v-if="user && dialog && typeof dialog === 'object' && roleAssign" :user="user" :role="dialog.revoke" :on-refresh="refreshRoles" @close="dialog = null" @done="done('角色绑定已解除。', 'roles')" />
  </KbDrawer>
</template>
