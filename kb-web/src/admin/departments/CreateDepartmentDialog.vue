<script setup lang="ts">
import { reactive, ref } from 'vue';
import { ElButton, ElForm, ElFormItem, ElInput, type FormInstance } from 'element-plus';
import { adminWrites } from '../api';
import { useAdminWrite } from '../hooks';
import { settle } from '../ui';
import type { AdminDepartment } from '../types';
import AdminModal from '../AdminModal.vue';
import WriteNotice from '../WriteNotice.vue';
import Note from '../Note.vue';

const props = defineProps<{ parent: AdminDepartment | null; onRefresh?: () => void }>();
const emit = defineEmits<{ close: []; done: [value: AdminDepartment] }>();
const write = useAdminWrite(() => `department-create:${props.parent?.departmentId ?? 'root'}`), form = ref<FormInstance>(), values = reactive({ code: '', name: '' });
let lock = false;
async function submit() {
  if (lock) {
    return;
  }
  lock = true;
  try {
    if (await form.value?.validate().catch(() => false)) {
      settle(await write.submit<AdminDepartment>(() => adminWrites.createDepartment({ ...values, parentId: props.parent?.departmentId ?? null })), value => emit('done', value), props.onRefresh);
    }
  } finally {
    lock = false;
  }
}
const retry = async () => settle(await write.retry<AdminDepartment>(), value => emit('done', value), props.onRefresh);
</script>

<template>
  <AdminModal :title="parent ? `在 ${parent.name} 下新建子部门` : '新建根部门'" :write="write" @close="emit('close')"><WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
    <ElForm ref="form" :model="values" label-position="top" :disabled="write.busy" @submit.prevent="submit">
      <ElFormItem prop="code" label="部门编码" :rules="[{ required: true, message: '请输入部门编码' }]"><ElInput v-model="values.code" aria-label="部门编码" autocomplete="off" /></ElFormItem>
      <ElFormItem prop="name" label="部门名称" :rules="[{ required: true, message: '请输入部门名称' }]"><ElInput v-model="values.name" aria-label="部门名称" autocomplete="off" /></ElFormItem>
      <Note>部门编码创建后不可修改。{{ parent ? '' : '根部门没有上级部门。' }}</Note><ElButton type="primary" native-type="submit" :loading="write.busy">确认创建</ElButton>
    </ElForm>
  </AdminModal>
</template>
