<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElButton, ElForm, ElFormItem, ElOption, ElSelect } from 'element-plus';
import { adminWrites } from '../api';
import { useAdminWrite } from '../hooks';
import { settle } from '../ui';
import type { AdminDepartment } from '../types';
import { buildTree, departmentLabel, descendantsOf } from '../departmentTree';
import AdminModal from '../AdminModal.vue';
import WriteNotice from '../WriteNotice.vue';
import Note from '../Note.vue';

const props = defineProps<{ department: AdminDepartment; list: AdminDepartment[]; refreshing: boolean; onRefresh?: () => void }>();
const emit = defineEmits<{ close: []; done: [value: AdminDepartment] }>();
const ROOT = '__root__', target = ref<string | null>(null), write = useAdminWrite(() => `department-parent:${props.department.departmentId}`);
const options = computed(() => {
  const department = props.department, blocked = descendantsOf(buildTree(props.list), department.departmentId).add(department.departmentId);
  return [...(department.parentId == null ? [] : [{ value: ROOT, label: '设为根部门' }]),
    ...props.list.filter(item => item.enabled && !blocked.has(item.departmentId) && item.departmentId !== department.parentId).map(item => ({ value: item.departmentId, label: departmentLabel(item) }))];
});
const chosen = computed(() => target.value === ROOT ? '根部门' : props.list.find(item => item.departmentId === target.value)?.name);
async function submit() {
  if (target.value) {
    settle(await write.submit<AdminDepartment>(() => adminWrites.moveDepartment(props.department.departmentId, target.value === ROOT ? null : target.value!, props.department.version)), value => emit('done', value), props.onRefresh);
  }
}
const retry = async () => settle(await write.retry<AdminDepartment>(), value => emit('done', value), props.onRefresh);
</script>

<template>
  <AdminModal :title="`移动 ${department.name}`" :write="write" @close="emit('close')"><WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
    <ElForm label-position="top" :disabled="write.busy"><ElFormItem label="目标上级部门" for="department-move-target"><ElSelect id="department-move-target" v-model="target" aria-label="目标上级部门" placeholder="选择启用的目标部门"><ElOption v-for="item in options" :key="item.value" :value="item.value" :label="item.label" /></ElSelect></ElFormItem></ElForm>
    <p v-if="chosen">确认将 {{ department.name }} 及其全部后代移动到“{{ chosen }}”之下？后代的层级路径会随之变化。</p>
    <Note>本节点、后代和停用部门不可作为目标；最终层级由服务器校验。</Note>
    <ElButton type="primary" :loading="write.busy" :disabled="!target || refreshing" @click="submit">确认移动</ElButton>
  </AdminModal>
</template>
