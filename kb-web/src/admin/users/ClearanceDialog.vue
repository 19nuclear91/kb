<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { ElButton, ElForm, ElFormItem, ElOption, ElSelect, type FormInstance } from 'element-plus';
import { useSession } from '../../SessionProvider';
import { adminPaths, adminWrites } from '../api';
import { useAdminResource, useAdminWrite } from '../hooks';
import { settle } from '../ui';
import type { AdminUser } from '../types';
import AdminModal from '../AdminModal.vue';
import WriteNotice from '../WriteNotice.vue';
import KbAlert from '../../ui/KbAlert.vue';

const props = defineProps<{ user: AdminUser; refreshing: boolean; onRefresh?: () => void }>();
const emit = defineEmits<{ close: []; done: [] }>();
const auth = useSession(), write = useAdminWrite(() => `user-clearance:${props.user.userId}`), form = ref<FormInstance>(), values = reactive({ clearanceLevel: undefined as number | undefined });
const levels = useAdminResource<number[]>(signal => auth.session.json(adminPaths.securityLevels, { signal }), 'levels');
const options = computed(() => (levels.data ?? []).filter(level => level !== props.user.clearanceLevel));
let lock = false;
async function submit() {
  if (lock) {
    return;
  }
  lock = true;
  try {
    if (await form.value?.validate().catch(() => false)) {
      settle(await write.submit(() => adminWrites.changeClearance(props.user.userId, values.clearanceLevel!, props.user.version)), () => emit('done'), props.onRefresh);
    }
  } finally {
    lock = false;
  }
}
const retry = async () => settle(await write.retry(), () => emit('done'), props.onRefresh);
</script>

<template>
  <AdminModal :title="`调整 ${user.username} 的密级`" :write="write" @close="emit('close')"><WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
    <KbAlert v-if="levels.error" type="warning" :title="`密级字典读取失败：${levels.error}`" style="margin-bottom: 12px"><template #action><ElButton @click="levels.reload">重试</ElButton></template></KbAlert>
    <ElForm ref="form" :model="values" label-position="top" :disabled="write.busy" @submit.prevent="submit"><p>当前密级：密级 {{ user.clearanceLevel }}</p>
      <ElFormItem prop="clearanceLevel" label="目标密级" :rules="[{ required: true, message: '请选择密级' }]"><ElSelect v-model="values.clearanceLevel" aria-label="目标密级" :loading="levels.loading"><ElOption v-for="level in options" :key="level" :value="level" :label="`密级 ${level}`" /></ElSelect></ElFormItem>
      <ElButton type="primary" native-type="submit" :loading="write.busy" :disabled="refreshing || !levels.data">确认调整</ElButton></ElForm>
  </AdminModal>
</template>
