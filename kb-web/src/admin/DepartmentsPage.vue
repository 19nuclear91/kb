<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElAlert, ElButton, ElDescriptions, ElDescriptionsItem, ElSpace, ElTag } from 'element-plus';
import Feedback from '../ui/Feedback.vue';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from './access';
import { adminPaths } from './api';
import { useAdminResource } from './hooks';
import type { AdminDepartment } from './types';
import Note from './Note.vue';
import { buildTree, departmentLabel } from './departmentTree';
import DepartmentNode from './departments/DepartmentNode.vue';
import CreateDepartmentDialog from './departments/CreateDepartmentDialog.vue';
import RenameDepartmentDialog from './departments/RenameDepartmentDialog.vue';
import MoveDepartmentDialog from './departments/MoveDepartmentDialog.vue';
import EnabledDepartmentDialog from './departments/EnabledDepartmentDialog.vue';

type Dialog = 'create-root' | 'create-child' | 'rename' | 'move' | 'enabled';
const auth = useSession(), access = useAdminAccess();
const selected = ref<string | null>(null), dialog = ref<Dialog | null>(null), receipt = ref('');
const list = useAdminResource<AdminDepartment[]>(signal => auth.session.json(adminPaths.departments, { signal }), 'departments');
const node = useAdminResource<AdminDepartment>(computed(() => selected.value ? signal => auth.session.json(adminPaths.department(selected.value!), { signal }) : null), () => `department:${selected.value ?? ''}`);
const nodeMissing = computed(() => node.status === 404), tree = computed(() => buildTree(list.data ?? []));
const write = computed(() => access.has(perm.deptWrite)), current = computed(() => node.data);
watch(nodeMissing, missing => {
  if (missing) {
    list.reload();
  }
});
const usable = computed(() => write.value && (dialog.value === null || dialog.value === 'create-root' || !!current.value));
watch(usable, value => {
  if (!value) {
    dialog.value = null;
  }
});
function refresh() {
  list.reload(); node.reload();
}
function done(text: string, select?: string) {
  dialog.value = null; receipt.value = text;
  if (select) {
    selected.value = select;
  }
  refresh();
}
function select(id: string) {
  if (!dialog.value) {
    receipt.value = ''; selected.value = id;
  }
}
function create() {
  receipt.value = ''; dialog.value = 'create-root';
}
const parentText = computed(() => {
  const id = current.value?.parentId;
  if (id == null) {
    return '根部门';
  }
  const parent = list.data?.find(item => item.departmentId === id);
  return parent ? departmentLabel(parent) : `部门 ID ${id}`;
});
</script>

<template>
  <PageHeader title="部门管理" description="按上级关系浏览部门；新建、改名、移动与启停逐项确认"><template #extra><ElButton v-if="write" type="primary" :disabled="!list.data" @click="create">新建根部门</ElButton></template></PageHeader>
  <ElAlert v-if="receipt" type="success" show-icon :title="receipt" style="margin-bottom: 16px" @close="receipt = ''" />
  <div class="admin-columns"><section class="panel"><h2>部门树</h2>
    <template v-if="list.data">
      <KbAlert v-if="list.error" type="error" :title="list.error" style="margin-bottom: 12px"><template #action><ElButton @click="list.reload">重新读取</ElButton></template></KbAlert>
      <ul v-if="tree.roots.length" class="admin-tree"><DepartmentNode v-for="root in tree.roots" :key="root.departmentId" :item="root" :tree="tree" :selected="selected" :seen="new Set()" @select="select" /></ul>
      <Feedback v-else empty />
    </template><Feedback v-else :loading="list.loading" :error="list.error" :retry="list.reload" />
  </section><section class="panel">
    <p v-if="!selected" class="muted">选择左侧部门查看详情。</p>
    <KbAlert v-else-if="nodeMissing" type="warning" show-icon title="该部门不存在或当前不可访问，部门树已重新读取。"><template #action><ElButton @click="selected = null">清除选择</ElButton></template></KbAlert>
    <Feedback v-else-if="!current" :loading="node.loading" :error="node.error" :retry="node.reload" />
    <template v-else><h2>{{ current.name }}</h2>
      <ElDescriptions :column="1" size="small"><ElDescriptionsItem label="编码">{{ current.code }}</ElDescriptionsItem><ElDescriptionsItem label="上级部门">{{ parentText }}</ElDescriptionsItem>
        <ElDescriptionsItem label="状态"><ElTag :type="current.enabled ? 'success' : 'info'">{{ current.enabled ? '启用' : '已停用' }}</ElTag></ElDescriptionsItem></ElDescriptions>
      <KbAlert v-if="node.error" type="error" :title="node.error" style="margin-top: 12px"><template #action><ElButton @click="node.reload">重新读取</ElButton></template></KbAlert>
      <template v-if="write"><ElSpace wrap style="margin-top: 16px">
        <ElButton :disabled="!current.enabled || node.loading" @click="dialog = 'create-child'">新建子部门</ElButton><ElButton :disabled="node.loading" @click="dialog = 'rename'">改名</ElButton>
        <ElButton :disabled="node.loading || !list.data" @click="dialog = 'move'">移动</ElButton><ElButton :type="current.enabled ? 'danger' : undefined" :plain="current.enabled" :disabled="node.loading" @click="dialog = 'enabled'">{{ current.enabled ? '停用部门' : '启用部门' }}</ElButton>
      </ElSpace><Note v-if="!current.enabled">停用部门下不能新建子部门。</Note></template>
    </template>
  </section></div>
  <CreateDepartmentDialog v-if="write && dialog === 'create-root'" :parent="null" @close="dialog = null" @done="done(`部门 ${$event.name} 已创建。`, $event.departmentId)" />
  <CreateDepartmentDialog v-if="write && current && dialog === 'create-child'" :parent="current" :on-refresh="refresh" @close="dialog = null" @done="done(`部门 ${$event.name} 已创建。`, $event.departmentId)" />
  <RenameDepartmentDialog v-if="write && current && dialog === 'rename'" :department="current" :refreshing="node.loading" :on-refresh="refresh" @close="dialog = null" @done="done('部门名称已更新。')" />
  <MoveDepartmentDialog v-if="write && current && list.data && dialog === 'move'" :department="current" :list="list.data" :refreshing="node.loading || list.loading" :on-refresh="refresh" @close="dialog = null" @done="done('部门已移动，部门树已重新读取。')" />
  <EnabledDepartmentDialog v-if="write && current && dialog === 'enabled'" :department="current" :refreshing="node.loading" :on-refresh="refresh" @close="dialog = null" @done="done($event ? '部门已启用。' : '部门已停用。')" />
</template>
