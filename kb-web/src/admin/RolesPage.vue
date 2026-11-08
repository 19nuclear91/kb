<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import { ElAlert, ElButton, ElCheckbox, ElSpace } from 'element-plus';
import Feedback from '../ui/Feedback.vue';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import KbDialog from '../ui/KbDialog.vue';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from './access';
import { adminPaths, adminWrites } from './api';
import { useAdminResource, useAdminWrite, type WriteOutcome } from './hooks';
import type { PermissionSummary, RoleDetail, RoleSummary } from './types';
import { groupPermissions } from './roleGroups';
import Note from './Note.vue';
import WriteNotice from './WriteNotice.vue';
import CreateRoleDialog from './roles/CreateRoleDialog.vue';
import RenameRoleDialog from './roles/RenameRoleDialog.vue';
import DeleteRoleDialog from './roles/DeleteRoleDialog.vue';

type Dialog = 'create' | 'rename' | 'delete';
type Pending = { permission: string; description: string; granted: boolean };
const auth = useSession(), access = useAdminAccess(), roleWrite = computed(() => access.has(perm.roleWrite));
const selected = ref<string | null>(null), dialog = ref<Dialog | null>(null), receipt = ref(''), pending = shallowRef<Pending | null>(null);
const roles = useAdminResource<RoleSummary[]>(signal => auth.session.json(adminPaths.roles, { signal }), 'roles');
const catalog = useAdminResource<PermissionSummary[]>(signal => auth.session.json(adminPaths.permissions, { signal }), 'permissions');
const detail = useAdminResource<RoleDetail>(computed(() => selected.value ? signal => auth.session.json(adminPaths.role(selected.value!), { signal }) : null), () => `role:${selected.value ?? ''}`);
const toggle = useAdminWrite(() => `role-permission:${selected.value ?? ''}`);
const missing = computed(() => detail.status === 404), role = computed(() => detail.data);
watch(missing, value => {
  if (value) {
    roles.reload();
  }
});
const granted = computed(() => new Set(role.value?.permissions ?? [])), known = computed(() => new Set((catalog.data ?? []).map(item => item.code)));
const canWrite = computed(() => roleWrite.value && !!role.value), ask = computed(() => canWrite.value ? pending.value : null);
const locked = computed(() => toggle.pending || ask.value !== null);
watch([canWrite, roleWrite], ([allowed, write]) => {
  if (!allowed) {
    pending.value = null;
    dialog.value = dialog.value === 'create' && write ? dialog.value : null;
  }
});
function finish(outcome: WriteOutcome<unknown>) {
  if (outcome.status === 'confirmed') {
    receipt.value = '权限变更已生效于后续请求，已重新读取角色详情。';
    detail.reload();
    access.check(true);
  } else if (outcome.status === 'rejected') {
    detail.reload();
  }
}
async function confirmToggle() {
  if (!ask.value || !selected.value) {
    return;
  }
  const { permission, granted } = ask.value, roleId = selected.value;
  pending.value = null;
  finish(await toggle.submit(() => adminWrites.setRolePermission(roleId, permission, granted)));
}
function done(text: string, next?: string | null) {
  dialog.value = null;
  receipt.value = text;
  if (next !== undefined) {
    selected.value = next;
  }
  roles.reload(); detail.reload();
}
function select(id: string) {
  if (!dialog.value) {
    receipt.value = '';
    selected.value = id;
  }
}
function create() {
  receipt.value = '';
  dialog.value = 'create';
}
function permissionChange(item: PermissionSummary) {
  return (value: string | number | boolean, event?: Event) => {
    // 勾选仅打开确认，显示值始终来自服务器；Element Plus 不会在 prop 未变时复原原生勾选。
    if (event) {
      (event.target as HTMLInputElement).checked = granted.value.has(item.code);
    }
    if (roleWrite.value && !locked.value && !detail.loading) {
      pending.value = { permission: item.code, description: item.description, granted: value === true };
    }
  };
}
const retry = () => { void toggle.retry().then(finish); };
const refresh = () => { roles.reload(); detail.reload(); };
</script>

<template>
  <PageHeader title="角色管理" description="查看角色及其功能权限；授权与撤权逐项确认、逐项提交" />
  <ElAlert v-if="receipt" type="success" show-icon :title="receipt" style="margin-bottom: 16px" @close="receipt = ''" />
  <div class="admin-columns"><section class="panel"><h2>角色目录</h2>
    <ElButton v-if="roleWrite" style="margin-bottom: 12px" :disabled="locked" @click="create">新建角色</ElButton>
    <template v-if="roles.data"><ul v-if="roles.data.length" class="admin-list"><li v-for="item in roles.data" :key="item.roleId">
      <button type="button" class="tree-node" :aria-current="selected === item.roleId ? 'true' : undefined" :disabled="locked" @click="select(item.roleId)">{{ item.name }} <span class="muted">{{ item.code }}</span></button>
    </li></ul><Feedback v-else empty /></template>
    <Feedback v-else :loading="roles.loading" :error="roles.error" :retry="roles.reload" />
    <KbAlert v-if="roles.data && roles.error" type="error" :title="roles.error" style="margin-top: 12px"><template #action><ElButton @click="roles.reload">重新读取</ElButton></template></KbAlert>
    <Note v-if="locked">当前角色有待确认的权限变更，处理完毕前不能切换或修改角色。</Note>
  </section><section class="panel">
    <p v-if="!selected" class="muted">选择左侧角色查看功能权限。</p>
    <KbAlert v-else-if="missing" type="warning" show-icon title="该角色不存在或已被删除，角色目录已重新读取。"><template #action><ElButton @click="selected = null">清除选择</ElButton></template></KbAlert>
    <Feedback v-else-if="!role" :loading="detail.loading" :error="detail.error" :retry="detail.reload" />
    <template v-else><h2>{{ role.name }} <span class="muted">{{ role.code }}</span></h2>
      <ElSpace v-if="roleWrite" style="margin-bottom: 12px"><ElButton :disabled="locked || detail.loading" @click="dialog = 'rename'">改名</ElButton><ElButton type="danger" plain :disabled="locked || detail.loading" @click="dialog = 'delete'">删除角色</ElButton></ElSpace>
      <KbAlert v-if="detail.error" type="error" :title="detail.error" style="margin-bottom: 12px"><template #action><ElButton @click="detail.reload">重新读取</ElButton></template></KbAlert>
      <WriteNotice :write="toggle" :on-retry="retry" :on-verify="detail.reload" />
      <Note>功能权限只控制管理与操作入口，不授予空间或文档正文的阅读资格；勾选即逐项提交，没有批量保存。</Note>
      <template v-if="catalog.data"><template v-if="catalog.data.length">
        <fieldset v-for="group in groupPermissions(catalog.data)" :key="group.prefix" class="permission-group"><legend>{{ group.name }}</legend>
          <div v-for="item in group.items" :key="item.code" class="permission-row"><ElCheckbox :model-value="granted.has(item.code)" :disabled="!roleWrite || locked || detail.loading" v-on="{ change: permissionChange(item) }">{{ item.description }} <span class="muted">{{ item.code }}</span></ElCheckbox></div>
        </fieldset>
      </template><Feedback v-else empty /></template>
      <Feedback v-else :loading="catalog.loading" :error="catalog.error" :retry="catalog.reload" />
      <Note v-if="role.permissions.some(code => catalog.data && !known.has(code))">目录外的已授予权限：{{ role.permissions.filter(code => !known.has(code)).join('、') }}</Note>
    </template>
  </section></div>
  <KbDialog v-if="ask && role" :title="ask.granted ? '授予权限' : '撤销权限'" open @close="pending = null">
    <p>{{ ask.granted ? '为' : '从' }}角色“{{ role.name }}”{{ ask.granted ? '授予' : '撤销' }}“{{ ask.description }}”（{{ ask.permission }}）？本次只提交这一项。</p>
    <template #footer><ElSpace><ElButton @click="pending = null">取消</ElButton><ElButton type="primary" @click="confirmToggle">{{ ask.granted ? '确认授予' : '确认撤销' }}</ElButton></ElSpace></template>
  </KbDialog>
  <CreateRoleDialog v-if="roleWrite && dialog === 'create'" @close="dialog = null" @done="done(`角色 ${$event.name} 已创建，当前没有任何功能权限。`, $event.roleId)" />
  <RenameRoleDialog v-if="roleWrite && role && dialog === 'rename'" :role="role" :on-refresh="detail.reload" @close="dialog = null" @done="done('角色名称已更新。')" />
  <DeleteRoleDialog v-if="roleWrite && role && dialog === 'delete'" :role="role" :on-refresh="refresh" @close="dialog = null" @done="done(`角色 ${role!.name} 已删除。`, null)" />
</template>
