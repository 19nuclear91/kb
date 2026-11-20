<script setup lang="ts">
import { computed, ref, shallowRef, useTemplateRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../../SessionProvider';
import { useResource } from '../../useResource';
import { useAdminWrite, type WriteOutcome } from '../../admin/hooks';
import ResourceStatus from '../../admin/ResourceStatus.vue';
import AdminModal from '../../admin/AdminModal.vue';
import WriteNotice from '../../admin/WriteNotice.vue';
import { isDocumentAccessDenied, metadataApi, metadataWrite, type Metadata, type Receipt } from './api';
import MetadataForm from './MetadataForm.vue';

const props = defineProps<{ documentId: string; canEdit: boolean; sourceVersionId?: string | null; versionNo?: number | null }>();
const emit = defineEmits<{ close: []; saved: []; denied: [status: number] }>();
const auth = useSession(), write = useAdminWrite(() => `metadata:${props.documentId}`, metadataWrite);
const form = useTemplateRef('metadataForm');
const context = computed(() => `${auth.state.epoch}:${props.documentId}`);
const lastRead = shallowRef<Metadata>(), refreshSource = ref(false);
const source = shallowRef({ id: props.sourceVersionId, versionNo: props.versionNo });
const denied = ref(false);
watch(() => [props.sourceVersionId, props.versionNo] as const, ([id, versionNo]) => { source.value = { id, versionNo }; });
const query = useResource(async signal => {
  const target = context.value;
  function onReadError(error: unknown): never {
    if (!signal.aborted && target === context.value && isDocumentAccessDenied(error)) {
      deny(error.status);
    }
    throw error;
  }
  const [metadata, published] = await Promise.all([
    metadataApi.get(auth.session, props.documentId, signal).catch(onReadError),
    refreshSource.value ? metadataApi.publishedVersion(auth.session, props.documentId, signal).catch(onReadError) : undefined,
  ]);
  return { metadata, published };
}, context);
function deny(status: number) {
  if (denied.value) return;
  denied.value = true;
  lastRead.value = undefined;
  emit('denied', status);
}
watch(context, () => {
  denied.value = false;
  lastRead.value = undefined;
  refreshSource.value = false;
  source.value = { id: props.sourceVersionId, versionNo: props.versionNo };
}, { flush: 'sync' });
watch(() => query.data, data => {
  if (data && !denied.value && props.canEdit && auth.state.phase === 'ready') {
    lastRead.value = data.metadata;
    if (data.published) source.value = data.published;
  }
});
watch(() => props.canEdit && auth.state.phase === 'ready', allowed => {
  if (!allowed) lastRead.value = undefined;
  else query.reload();
}, { flush: 'sync' });
function reload() {
  refreshSource.value = true;
  query.reload();
}
watch(() => [query.status, write.status], values => {
  const status = values.find(value => value === 401 || value === 403 || value === 404);
  if (status) deny(status);
});
const allowed = computed(() => props.canEdit && !denied.value);
function receive(result: WriteOutcome<Receipt>) {
  if (result.status === 'confirmed') {
    emit('saved');
  } else if (result.status === 'rejected' && [401, 403, 404].includes(result.error.status) && !denied.value) {
    deny(result.error.status);
  }
}
</script>

<template>
  <AdminModal title="编辑整文档资料" :write="write" @close="emit('close')">
    <WriteNotice :write="write" :disabled="!form || !allowed || write.blocked" :on-retry="() => form?.retry()" :on-verify="reload" />
    <ResourceStatus :loading="query.loading" :error="query.error" :retry="reload" /><ElButton :disabled="write.busy || query.loading || !allowed" @click="reload">重新读取资料</ElButton>
    <MetadataForm v-if="lastRead && allowed && auth.state.phase === 'ready'" ref="metadataForm" :key="context" :data="lastRead"
      :source-version-id="source.id" :version-no="source.versionNo" :write="write" :can-edit="!!query.data" @denied="deny" @receive="receive" />
    <ElButton v-else-if="denied" disabled>保存资料</ElButton>
  </AdminModal>
</template>
