<script setup lang="ts">
import { useRouter } from 'vue-router';
import KbAlert from '../ui/KbAlert.vue';
import { useSession } from '../SessionProvider';
import type { QueryPage } from '../types';
import { useAdminAccess } from './access';
import { useAdminResource } from './hooks';
import { spacePaths, spaceWrites, type AdminSpace, type ManagedDocument } from './spaceApi';
import ResourceStatus from './ResourceStatus.vue';
import ResourceWriteDialog from './ResourceWriteDialog.vue';

const props = defineProps<{ id: string; refresh: () => void }>();
const emit = defineEmits<{ close: [] }>();
const auth = useSession(), access = useAdminAccess(), router = useRouter();
const probe = useAdminResource<QueryPage<ManagedDocument>>(signal => auth.session.json(spacePaths.documents({ spaceId: props.id, limit: 1 }), { signal }), () => `delete:${props.id}`);
function leave() {
  access.check(true); void router.replace('/admin/spaces');
}
async function verify() {
  try {
    const current = await auth.session.json<AdminSpace>(spacePaths.space(props.id));
    if (!current.manageable) {
      props.refresh(); access.check(true); emit('close');
    } else {
      probe.reload();
    }
  } catch (error) {
    if (error && typeof error === 'object' && 'status' in error && error.status === 404) {
      leave();
    } else {
      props.refresh(); access.check(true); probe.reload();
    }
  }
}
function refresh() {
  probe.reload(); void verify();
}
</script>

<template><ResourceWriteDialog title="删除空间" :scope="`space:${id}:delete`" :on-refresh="refresh" :disabled="probe.loading || !!probe.error || !probe.data || probe.data.items.length > 0" :build="() => spaceWrites.delete(id)" :on-done="leave" @close="emit('close')">
  <ResourceStatus :loading="probe.loading" :error="probe.error" :retry="probe.reload" /><KbAlert v-if="probe.data?.items.length" type="info" title="空间仍有文档，请改用归档。" /><p>删除不可恢复，空间授权将一并移除。此检查仅减少必然失败的提交，以服务端最终判定为准。</p>
</ResourceWriteDialog></template>
