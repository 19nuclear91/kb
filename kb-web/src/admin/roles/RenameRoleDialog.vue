<script setup lang="ts">
import { reactive, ref } from 'vue';
import { ElButton, ElForm, ElFormItem, ElInput, type FormInstance } from 'element-plus';
import { adminWrites } from '../api';
import { useAdminWrite } from '../hooks';
import { settle } from '../ui';
import type { RoleDetail } from '../types';
import AdminModal from '../AdminModal.vue';
import WriteNotice from '../WriteNotice.vue';

const props = defineProps<{ role: RoleDetail; onRefresh?: () => void }>();
const emit = defineEmits<{ close: []; done: [value: RoleDetail] }>();
const write = useAdminWrite(() => `role-name:${props.role.roleId}`), form = ref<FormInstance>(), values = reactive({ name: props.role.name });
let lock = false;
async function submit() {
  if (lock) {
    return;
  }
  lock = true;
  try {
    if (await form.value?.validate().catch(() => false)) {
      settle(await write.submit<RoleDetail>(() => adminWrites.renameRole(props.role.roleId, values.name)), value => emit('done', value), props.onRefresh);
    }
  } finally {
    lock = false;
  }
}
const retry = async () => settle(await write.retry<RoleDetail>(), value => emit('done', value), props.onRefresh);
</script>

<template>
  <AdminModal title="角色改名" :write="write" @close="emit('close')"><WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
    <ElForm ref="form" :model="values" label-position="top" :disabled="write.busy" @submit.prevent="submit">
      <p>角色编码：{{ role.code }}（只读）</p>
      <ElFormItem prop="name" label="新名称" :rules="[{ required: true, message: '请输入角色名称' }]"><ElInput v-model="values.name" aria-label="新名称" autocomplete="off" /></ElFormItem>
      <ElButton type="primary" native-type="submit" :loading="write.busy">确认改名</ElButton>
    </ElForm>
  </AdminModal>
</template>
