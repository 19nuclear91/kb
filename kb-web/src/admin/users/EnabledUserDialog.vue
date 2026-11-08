<script setup lang="ts">
import { ElButton } from 'element-plus';
import { adminWrites } from '../api';
import { useAdminWrite } from '../hooks';
import { settle } from '../ui';
import type { AdminUser } from '../types';
import AdminModal from '../AdminModal.vue';
import WriteNotice from '../WriteNotice.vue';
import Note from '../Note.vue';

const props = defineProps<{ user: AdminUser; refreshing: boolean; onRefresh?: () => void }>();
const emit = defineEmits<{ close: []; done: [value: boolean] }>();
const write = useAdminWrite(() => `user-enabled:${props.user.userId}`), target = !props.user.enabled;
const submit = async () => settle(await write.submit(() => adminWrites.setUserEnabled(props.user.userId, target, props.user.version)), () => emit('done', target), props.onRefresh);
const retry = async () => settle(await write.retry(), () => emit('done', target), props.onRefresh);
</script>

<template>
  <AdminModal :title="target ? '启用账号' : '停用账号'" :write="write" @close="emit('close')"><WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
    <p>{{ target ? `启用后 ${user.username} 可以重新登录。` : `停用后 ${user.username} 将无法登录，已有会话随后失效。` }}</p><Note v-if="user.enabled === target">重新读取显示账号已处于该状态，可关闭窗口。</Note>
    <ElButton :type="target ? 'primary' : 'danger'" :loading="write.busy" :disabled="refreshing" @click="submit">{{ target ? '确认启用' : '确认停用' }}</ElButton>
  </AdminModal>
</template>
