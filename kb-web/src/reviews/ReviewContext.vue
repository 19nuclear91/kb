<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import { ElButton, ElSpace, ElTag } from 'element-plus';
import { RouterLink } from 'vue-router';
import VersionPreview from '../office/VersionPreview.vue';
import DownloadButton from '../DownloadButton.vue';
import { useSession } from '../SessionProvider';
import { useDocumentOperations } from '../documents/OperationContext';
import { useResource } from '../useResource';
import Feedback from '../ui/Feedback.vue';
import KbAlert from '../ui/KbAlert.vue';
import { reviewPaths, type ReviewCommand, type ReviewReceipt, type ReviewState, type ReviewStatus } from './api';
import { commands, reviewLabel } from './labels';
import Opinions from './Opinions.vue';
import CommandDialog from './CommandDialog.vue';

const props = defineProps<{ documentId: string; versionId: string; files: boolean }>();
const emit = defineEmits<{ changed: []; status: [value: ReviewStatus | undefined]; denied: [] }>();
const auth = useSession(), documents = useDocumentOperations();
const query = useResource(signal => auth.session.json<ReviewStatus>(reviewPaths.status(props.documentId, props.versionId), { signal }), () => `${auth.state.epoch}:${props.documentId}/${props.versionId}`);
const current = computed(() => !query.loading && !query.error ? query.data : undefined);
const opinions = ref(false), preview = ref(false);
const chosen = shallowRef<{ command: ReviewCommand; state: ReviewState } | null>(null), receipt = ref('');
watch(() => query.status, status => {
  if (status && [401, 403, 404].includes(status)) {
    emit('denied');
  }
});
watch(current, value => emit('status', value), { immediate: true });
watch([current, () => query.error], ([value, error]) => {
  if (!value?.capabilities.canReadOpinions) {
    opinions.value = false;
  }
  if (!value?.capabilities.canRead) {
    preview.value = false;
  }
  if (error) {
    chosen.value = null;
    receipt.value = '';
  }
});
function refresh() {
  opinions.value = false;
  preview.value = false;
  query.reload();
}
function done(value: ReviewReceipt) {
  chosen.value = null;
  receipt.value = `操作回执已确认：版本 ${value.versionId}${value.roundNo ? `，轮次 ${value.roundNo}` : ''}。`;
  refresh();
  emit('changed');
}
const filePath = computed(() => `/api/documents/${props.documentId}/versions/${props.versionId}`);
const available = computed(() => commands.filter(([, ability]) => current.value?.capabilities[ability]));
</script>

<template>
  <section class="panel"><h2>版本审核状态</h2><Feedback :loading="query.loading" :error="query.error" :retry="refresh" />
    <ElButton @click="refresh">重新核对版本</ElButton>
    <KbAlert v-if="receipt" type="success" :title="receipt" />
    <template v-if="current">
      <p><ElTag type="info">{{ reviewLabel(current.state) }}</ElTag>固定策略：{{ current.state.policy === 'REVIEW_REQUIRED' ? '需审核' : '自动发布' }} · 本轮：{{ current.state.roundNo ?? '尚未送审' }}</p>
      <p v-if="current.capabilities.canRead">当前发布版本：{{ current.state.currentVersionId ?? '尚无版本' }}</p>
      <p v-else>当前仅可查看本人最小状态；没有正文、文件或私有意见读取资格。</p>
      <ElSpace wrap>
        <ElButton v-for="[command, , label] in available" :key="command" :aria-label="label" :disabled="documents.state.exclusive" @click="chosen = { command, state: { ...current!.state } }">{{ label }}</ElButton>
        <template v-if="files && current.capabilities.canRead"><RouterLink :to="`/documents/${documentId}`">打开文档</RouterLink><ElButton @click="preview = true">预览此版本</ElButton></template>
        <DownloadButton v-if="files && current.capabilities.canRead && current.capabilities.canDownload" :path="`${filePath}/download`" :name="`版本-${current.state.versionNo}`" @denied="refresh" />
        <ElButton v-if="current.capabilities.canReadOpinions" @click="opinions = !opinions">{{ opinions ? '关闭私有意见' : '查看私有意见' }}</ElButton>
      </ElSpace>
      <Opinions v-if="opinions && current.capabilities.canReadOpinions" :key="`${documentId}/${versionId}`" :document-id="documentId" :version-id="versionId" @denied="refresh" />
    </template>
    <CommandDialog v-if="chosen" :key="`${documentId}/${versionId}/${chosen.command}`" :command="chosen.command" :target="chosen.state" :current="current" :close="() => { chosen = null; }" :refresh="refresh" :done="done" />
    <VersionPreview v-if="files" :path="preview && current?.capabilities.canRead ? `${filePath}/preview` : null" @close="preview = false" @denied="refresh" />
  </section>
</template>
