<script setup lang="ts">
import { computed, reactive, ref, shallowRef, watch } from 'vue';
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElOption, ElSelect, ElTable, ElTableColumn, ElTag } from 'element-plus';
import Feedback from '../ui/Feedback.vue';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import { useSession } from '../SessionProvider';
import type { QueryPage } from '../types';
import { perm, useAdminAccess } from './access';
import { adminPaths, searchPath, searchText } from './api';
import { useAdminResource, useCursorList } from './hooks';
import type { AdminDepartment, AdminUser, UserFilters } from './types';
import Note from './Note.vue';
import { departmentLabel, departmentText } from './users/labels';
import UserDetail from './users/UserDetail.vue';
import CreateUserDialog from './users/CreateUserDialog.vue';

const auth = useSession(), access = useAdminAccess();
const filters = shallowRef<UserFilters>({}), filterError = ref(''), selected = ref<string | null>(null), creating = ref(false), receipt = ref('');
const draft = reactive({ q: '', departmentId: undefined as string | undefined, enabled: undefined as string | undefined });
const deptRead = computed(() => access.has(perm.deptRead)), userWrite = computed(() => access.has(perm.userWrite));
watch(userWrite, value => {
  if (!value) {
    creating.value = false;
  }
});
const departments = useAdminResource<AdminDepartment[]>(computed(() => deptRead.value ? signal => auth.session.json(adminPaths.departments, { signal }) : null), 'departments');
const key = computed(() => JSON.stringify(filters.value));
const list = useCursorList<AdminUser>((cursor, signal) => auth.session.json<QueryPage<AdminUser>>(searchPath(filters.value, cursor), { signal }), key);
function search() {
  let q: string | undefined;
  try {
    q = searchText(draft.q);
  } catch {
    filterError.value = '关键词最多 128 个字符，且不能包含控制字符。';
    return;
  }
  const next: UserFilters = { q, departmentId: draft.departmentId || undefined, enabled: draft.enabled === undefined ? undefined : draft.enabled === 'true' };
  filterError.value = '';
  if (JSON.stringify(next) === key.value) {
    list.reload();
  } else {
    filters.value = next;
  }
}
function create() {
  receipt.value = ''; creating.value = true;
}
function select(id: string) {
  receipt.value = ''; selected.value = id;
}
function created(user: AdminUser) {
  creating.value = false;
  receipt.value = `账号 ${user.username} 已创建，首次登录需修改密码；角色需在详情中单独分配。`;
  list.reload(); selected.value = user.userId;
}
</script>

<template>
  <PageHeader title="账号管理" description="按用户名或显示名检索账号，并逐项调整部门、状态、密码、密级与角色绑定"><template #extra><ElButton v-if="userWrite" type="primary" @click="create">新建账号</ElButton></template></PageHeader>
  <ElAlert v-if="receipt" type="success" show-icon :title="receipt" style="margin-bottom: 16px" @close="receipt = ''" />
  <section class="panel"><ElForm inline class="toolbar" @submit.prevent="search">
    <ElFormItem label="关键词"><ElInput v-model="draft.q" aria-label="关键词" clearable placeholder="用户名或显示名" style="width: 220px" /></ElFormItem>
    <ElFormItem v-if="deptRead" label="主部门"><ElSelect v-model="draft.departmentId" aria-label="主部门" clearable style="width: 220px" :loading="departments.loading" placeholder="全部部门"><ElOption v-for="item in departments.data ?? []" :key="item.departmentId" :value="item.departmentId" :label="departmentLabel(item)" /></ElSelect></ElFormItem>
    <ElFormItem label="状态"><ElSelect v-model="draft.enabled" aria-label="状态" clearable style="width: 140px" placeholder="全部状态"><ElOption value="true" label="启用" /><ElOption value="false" label="停用" /></ElSelect></ElFormItem>
    <ElButton native-type="submit">检索</ElButton>
  </ElForm>
    <KbAlert v-if="filterError" type="error" :title="filterError" style="margin-bottom: 12px" />
    <KbAlert v-if="deptRead && departments.error" type="warning" :title="`部门目录暂不可用：${departments.error}`" style="margin-bottom: 12px"><template #action><ElButton @click="departments.reload">重试</ElButton></template></KbAlert>
    <Note>主部门条件只精确匹配账号的主部门，不包含子部门；结果按游标继续读取，不提供总数。{{ !deptRead ? ' 缺少 identity:dept:read，部门筛选不可用，列表只显示部门 ID。' : '' }}</Note>
    <ElTable v-if="list.items.length" row-key="userId" :data="list.items"><ElTableColumn label="用户名" prop="username" /><ElTableColumn label="显示名" prop="displayName" />
      <ElTableColumn label="主部门"><template #default="{ row }">{{ departmentText(departments, row.departmentId) }}</template></ElTableColumn><ElTableColumn label="密级" prop="clearanceLevel" />
      <ElTableColumn label="状态"><template #default="{ row }"><ElTag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '已停用' }}</ElTag></template></ElTableColumn>
      <ElTableColumn label="操作"><template #default="{ row }"><ElButton link type="primary" :aria-label="`查看 ${row.username}`" @click="select(row.userId)">查看详情</ElButton></template></ElTableColumn>
    </ElTable><Feedback v-else :loading="list.loading" :error="list.error" :retry="list.reload" empty />
    <KbAlert v-if="list.items.length > 0 && list.error" type="error" :title="list.error" style="margin-top: 12px"><template #action><ElButton @click="list.more">重试读取</ElButton></template></KbAlert>
    <ElButton v-if="list.items.length > 0 && list.hasMore && !list.error" style="margin-top: 16px" :loading="list.loading" @click="list.more">继续读取</ElButton>
  </section>
  <UserDetail v-if="selected" :key="selected" :user-id="selected" :departments="departments" @close="selected = null" @changed="list.reload" />
  <CreateUserDialog v-if="creating && userWrite" :departments="departments" @close="creating = false" @done="created" />
</template>
