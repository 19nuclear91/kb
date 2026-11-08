<script setup lang="ts">
import { ElButton } from 'element-plus';
import { adminWrites } from '../api';
import { useAdminWrite } from '../hooks';
import { settle } from '../ui';
import type { RoleDetail } from '../types';
import AdminModal from '../AdminModal.vue';
import WriteNotice from '../WriteNotice.vue';

const props = defineProps<{ role: RoleDetail; onRefresh?: () => void }>();
const emit = defineEmits<{ close: []; done: [] }>();
const write = useAdminWrite(() => `role-delete:${props.role.roleId}`);
const submit = async () => settle(await write.submit(() => adminWrites.deleteRole(props.role.roleId)), () => emit('done'), props.onRefresh);
const retry = async () => settle(await write.retry(), () => emit('done'), props.onRefresh);
</script>

<template>
  <AdminModal title="删除角色" :write="write" @close="emit('close')"><WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
    <p>确认删除角色“{{ role.name }}”（{{ role.code }}）？仍有用户绑定或空间/文档授权引用时服务器会拒绝删除；本页不会自动解除这些引用。</p>
    <ElButton type="danger" :loading="write.busy" @click="submit">确认删除</ElButton>
  </AdminModal>
</template>
