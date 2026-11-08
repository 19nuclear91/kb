<script setup lang="ts">
import { reactive, ref } from 'vue';
import { ElButton, ElForm, ElFormItem, ElInput, type FormInstance } from 'element-plus';
import { adminWrites } from '../api';
import { useAdminWrite } from '../hooks';
import { settle } from '../ui';
import type { AdminDepartment } from '../types';
import AdminModal from '../AdminModal.vue';
import WriteNotice from '../WriteNotice.vue';

const props = defineProps<{ department: AdminDepartment; refreshing: boolean; onRefresh?: () => void }>();
const emit = defineEmits<{ close: []; done: [value: AdminDepartment] }>();
const write = useAdminWrite(() => `department-name:${props.department.departmentId}`), form = ref<FormInstance>(), values = reactive({ name: props.department.name });
let lock = false;
async function submit() {
  if (lock) {
    return;
  }
  lock = true;
  try {
    if (await form.value?.validate().catch(() => false)) {
      settle(await write.submit<AdminDepartment>(() => adminWrites.renameDepartment(props.department.departmentId, values.name, props.department.version)), value => emit('done', value), props.onRefresh);
    }
  } finally {
    lock = false;
  }
}
const retry = async () => settle(await write.retry<AdminDepartment>(), value => emit('done', value), props.onRefresh);
</script>

<template>
  <AdminModal title="部门改名" :write="write" @close="emit('close')"><WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
    <ElForm ref="form" :model="values" label-position="top" :disabled="write.busy" @submit.prevent="submit">
      <p>部门编码：{{ department.code }}（只读）</p><ElFormItem prop="name" label="新名称" :rules="[{ required: true, message: '请输入部门名称' }]"><ElInput v-model="values.name" aria-label="新名称" autocomplete="off" /></ElFormItem>
      <ElButton type="primary" native-type="submit" :loading="write.busy" :disabled="refreshing">确认改名</ElButton>
    </ElForm>
  </AdminModal>
</template>
