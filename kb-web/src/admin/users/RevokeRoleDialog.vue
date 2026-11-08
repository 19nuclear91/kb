<script setup lang="ts">
import { ElButton } from 'element-plus';
import { adminWrites } from '../api';
import { useAdminWrite } from '../hooks';
import { settle } from '../ui';
import type { AdminUser, RoleSummary } from '../types';
import AdminModal from '../AdminModal.vue';
import WriteNotice from '../WriteNotice.vue';

const props = defineProps<{ user: AdminUser; role: RoleSummary; onRefresh?: () => void }>();
const emit = defineEmits<{ close: []; done: [] }>();
const write = useAdminWrite(() => `user-role-revoke:${props.user.userId}:${props.role.roleId}`);
const submit = async () => settle(await write.submit(() => adminWrites.revokeRole(props.user.userId, props.role.roleId)), () => emit('done'), props.onRefresh);
const retry = async () => settle(await write.retry(), () => emit('done'), props.onRefresh);
</script>

<template><AdminModal title="解除角色绑定" :write="write" @close="emit('close')"><WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
  <p>确认解除 {{ user.username }} 的角色“{{ role.name }}”（{{ role.code }}）？该账号将失去此角色带来的功能权限。</p><ElButton type="danger" :loading="write.busy" @click="submit">确认解除</ElButton>
</AdminModal></template>
