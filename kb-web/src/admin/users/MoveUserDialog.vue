<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { ElButton, ElForm, ElFormItem, ElOption, ElSelect, type FormInstance } from 'element-plus';
import { adminWrites } from '../api';
import { useAdminWrite } from '../hooks';
import { settle } from '../ui';
import type { AdminUser } from '../types';
import AdminModal from '../AdminModal.vue';
import WriteNotice from '../WriteNotice.vue';
import Note from '../Note.vue';
import KbAlert from '../../ui/KbAlert.vue';
import { departmentLabel, departmentText, type Departments } from './labels';

const props = defineProps<{ user: AdminUser; departments: Departments; refreshing: boolean; onRefresh?: () => void }>();
const emit = defineEmits<{ close: []; done: [] }>();
const write = useAdminWrite(() => `user-department:${props.user.userId}`), form = ref<FormInstance>(), values = reactive({ departmentId: undefined as string | undefined });
const options = computed(() => (props.departments.data ?? []).filter(item => item.enabled && item.departmentId !== props.user.departmentId));
let lock = false;
async function submit() {
  if (lock) {
    return;
  }
  lock = true;
  try {
    if (await form.value?.validate().catch(() => false)) {
      settle(await write.submit(() => adminWrites.moveUser(props.user.userId, values.departmentId!, props.user.version)), () => emit('done'), props.onRefresh);
    }
  } finally {
    lock = false;
  }
}
const retry = async () => settle(await write.retry(), () => emit('done'), props.onRefresh);
</script>

<template>
  <AdminModal :title="`调整 ${user.username} 的主部门`" :write="write" @close="emit('close')"><WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
    <KbAlert v-if="departments.error" type="warning" :title="`部门候选读取失败：${departments.error}`" style="margin-bottom: 12px"><template #action><ElButton @click="departments.reload">重试</ElButton></template></KbAlert>
    <ElForm ref="form" :model="values" label-position="top" :disabled="write.busy" @submit.prevent="submit"><p>当前主部门：{{ departmentText(departments, user.departmentId) }}</p>
      <ElFormItem prop="departmentId" label="目标主部门" :rules="[{ required: true, message: '请选择启用的目标部门' }]"><ElSelect v-model="values.departmentId" aria-label="目标主部门" :loading="departments.loading" placeholder="选择启用的部门"><ElOption v-for="item in options" :key="item.departmentId" :value="item.departmentId" :label="departmentLabel(item)" /></ElSelect></ElFormItem>
      <Note>调整主部门不会修改密级，也不会改变角色绑定。</Note><ElButton type="primary" native-type="submit" :loading="write.busy" :disabled="refreshing || departments.loading">确认调整</ElButton>
    </ElForm>
  </AdminModal>
</template>
