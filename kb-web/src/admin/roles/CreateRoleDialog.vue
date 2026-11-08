<script setup lang="ts">
import { reactive, ref } from 'vue';
import { ElButton, ElForm, ElFormItem, ElInput, type FormInstance } from 'element-plus';
import { adminWrites } from '../api';
import { useAdminWrite } from '../hooks';
import { settle } from '../ui';
import type { RoleDetail } from '../types';
import AdminModal from '../AdminModal.vue';
import WriteNotice from '../WriteNotice.vue';
import Note from '../Note.vue';

const emit = defineEmits<{ close: []; done: [value: RoleDetail] }>();
const write = useAdminWrite('role-create'), form = ref<FormInstance>(), values = reactive({ code: '', name: '' });
let lock = false;
async function submit() {
  if (lock) {
    return;
  }
  lock = true;
  try {
    if (await form.value?.validate().catch(() => false)) {
      settle(await write.submit<RoleDetail>(() => adminWrites.createRole(values.code, values.name)), value => emit('done', value));
    }
  } finally {
    lock = false;
  }
}
const retry = async () => settle(await write.retry<RoleDetail>(), value => emit('done', value));
</script>

<template>
  <AdminModal title="新建角色" :write="write" @close="emit('close')"><WriteNotice :write="write" :on-retry="retry" />
    <ElForm ref="form" :model="values" label-position="top" :disabled="write.busy" @submit.prevent="submit">
      <ElFormItem prop="code" label="角色编码" :rules="[{ required: true, message: '请输入角色编码' }]"><ElInput v-model="values.code" aria-label="角色编码" autocomplete="off" /></ElFormItem>
      <ElFormItem prop="name" label="角色名称" :rules="[{ required: true, message: '请输入角色名称' }]"><ElInput v-model="values.name" aria-label="角色名称" autocomplete="off" /></ElFormItem>
      <Note>角色编码创建后不可修改；新角色没有任何功能权限，需逐项授予。</Note><ElButton type="primary" native-type="submit" :loading="write.busy">确认创建</ElButton>
    </ElForm>
  </AdminModal>
</template>
