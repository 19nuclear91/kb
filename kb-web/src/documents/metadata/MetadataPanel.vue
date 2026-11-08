<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../../SessionProvider';
import { useResource } from '../../useResource';
import ResourceStatus from '../../admin/ResourceStatus.vue';
import KbAlert from '../../ui/KbAlert.vue';
import { useDocumentOperations } from '../OperationContext';
import { metadataApi } from './api';
import MetadataEditor from './MetadataEditor.vue';

const props = defineProps<{ documentId: string; canEdit: boolean }>();
const emit = defineEmits<{ title: [title: string]; denied: [status: number] }>();
const auth = useSession(), documents = useDocumentOperations();
const query = useResource(signal => metadataApi.get(auth.session, props.documentId, signal), () => props.documentId);
const open = ref(false), saved = ref(false);
watch(() => query.status, status => {
  if (status === 403 || status === 404) {
    emit('denied', status);
  }
});
watch(() => query.data, data => {
  if (data) {
    emit('title', data.title);
  }
});
function receive() {
  open.value = false;
  saved.value = true;
  query.reload();
}
</script>

<template>
  <section class="panel"><h2>文档资料</h2><p class="muted">整文档资料即时生效，不随所选文件版本切换，也不进入文件审核。</p>
    <KbAlert v-if="saved" type="success" title="资料已保存；下方读取显示当前资料。" />
    <ResourceStatus :loading="query.loading" :error="query.error" :retry="query.reload" />
    <template v-if="query.data"><p style="white-space: pre-wrap">{{ query.data.summary ?? '暂无人工摘要' }}</p><p>分类：{{ query.data.category?.path.map(node => node.name).join(' / ') ?? '未分类' }}</p>
      <p>标签：{{ query.data.tags.map(tag => `${tag.name}${tag.enabled ? '' : '（停用）'}`).join('、') || '无' }}</p></template>
    <ElButton :disabled="!canEdit || documents.state.exclusive || query.loading || !query.data" @click="open = true">编辑资料</ElButton>
    <MetadataEditor v-if="open" :document-id="documentId" :can-edit="canEdit" @denied="emit('denied', $event)" @close="open = false" @saved="receive" />
  </section>
</template>
