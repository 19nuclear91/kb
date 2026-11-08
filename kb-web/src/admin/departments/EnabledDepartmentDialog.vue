<script setup lang="ts">
import { ElButton } from 'element-plus';
import { adminWrites } from '../api';
import { useAdminWrite } from '../hooks';
import { settle } from '../ui';
import type { AdminDepartment } from '../types';
import AdminModal from '../AdminModal.vue';
import WriteNotice from '../WriteNotice.vue';
import Note from '../Note.vue';

const props = defineProps<{ department: AdminDepartment; refreshing: boolean; onRefresh?: () => void }>();
const emit = defineEmits<{ close: []; done: [value: boolean] }>();
const write = useAdminWrite(() => `department-enabled:${props.department.departmentId}`), target = !props.department.enabled;
const submit = async () => settle(await write.submit(() => adminWrites.setDepartmentEnabled(props.department.departmentId, target, props.department.version)), () => emit('done', target), props.onRefresh);
const retry = async () => settle(await write.retry(), () => emit('done', target), props.onRefresh);
</script>

<template>
  <AdminModal :title="target ? '启用部门' : '停用部门'" :write="write" @close="emit('close')"><WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
    <p>{{ target ? `启用 ${department.name} 前，其上级部门必须处于启用状态。` : `停用 ${department.name} 前，需先处理其下启用的子部门和用户；本操作只作用于该部门，不会递归停用。` }}</p>
    <Note v-if="department.enabled === target">重新读取显示部门已处于该状态，可关闭窗口。</Note>
    <ElButton :type="target ? 'primary' : 'danger'" :loading="write.busy" :disabled="refreshing" @click="submit">{{ target ? '确认启用' : '确认停用' }}</ElButton>
  </AdminModal>
</template>
