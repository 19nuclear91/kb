<script setup lang="ts">
import { computed, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../../SessionProvider';
import { useResource } from '../../useResource';
import { useAdminWrite, type WriteOutcome } from '../../admin/hooks';
import ResourceStatus from '../../admin/ResourceStatus.vue';
import AdminModal from '../../admin/AdminModal.vue';
import WriteNotice from '../../admin/WriteNotice.vue';
import { metadataApi, metadataWrite, type Receipt } from './api';
import MetadataForm from './MetadataForm.vue';

const props = defineProps<{ documentId: string; canEdit: boolean }>();
const emit = defineEmits<{ close: []; saved: []; denied: [status: number] }>();
const auth = useSession(), write = useAdminWrite(() => `metadata:${props.documentId}`, metadataWrite);
const query = useResource(signal => metadataApi.get(auth.session, props.documentId, signal), () => props.documentId);
watch(() => query.status, status => {
  if (status === 403 || status === 404) {
    emit('denied', status);
  }
});
const allowed = computed(() => props.canEdit && ![403, 404].includes(write.status ?? 0) && ![403, 404].includes(query.status ?? 0));
function receive(result: WriteOutcome<Receipt>) {
  if (result.status === 'confirmed') {
    emit('saved');
  } else if (result.status === 'rejected' && [403, 404].includes(result.error.status)) {
    emit('denied', result.error.status);
  }
}
const retry = () => { void write.retry<Receipt>().then(receive); };
</script>

<template>
  <AdminModal title="编辑整文档资料" :write="write" @close="emit('close')">
    <WriteNotice :write="write" :disabled="!allowed || write.blocked" :on-retry="retry" :on-verify="query.reload" />
    <ResourceStatus :loading="query.loading" :error="query.error" :retry="query.reload" /><ElButton :disabled="write.busy" @click="query.reload">重新读取资料</ElButton>
    <MetadataForm v-if="query.data" :key="`${documentId}:${query.revision}`" :data="query.data" :write="write" :can-edit="allowed" @receive="receive" />
  </AdminModal>
</template>
