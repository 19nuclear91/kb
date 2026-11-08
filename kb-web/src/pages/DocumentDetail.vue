<script setup lang="ts">
import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { ElButton, ElSpace, ElTable, ElTableColumn, ElTag } from 'element-plus';
import { RouterLink } from 'vue-router';
import VersionPreview from '../office/VersionPreview.vue';
import { useSession } from '../SessionProvider';
import { useDocumentOperations } from '../documents/OperationContext';
import { operationFailure } from '../documents/operations';
import { canDelete } from '../documents/capabilities';
import MetadataPanel from '../documents/metadata/MetadataPanel.vue';
import { useResource } from '../useResource';
import { isAbort, messageOf, positiveId } from '../api';
import { progressLabel, retryable } from '../logic';
import Feedback from '../ui/Feedback.vue';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import DownloadButton from '../DownloadButton.vue';
import { perm, useAdminAccess } from '../admin/access';
import { useFavorites } from '../favorites/useFavorites';
import FavoriteButton from '../favorites/FavoriteButton.vue';
import type { ReviewStatus } from '../reviews/api';
import VersionReviewPanel from '../reviews/VersionReviewPanel.vue';
import VersionComments from '../comments/VersionComments.vue';
import ProgressCard from '../documents/ProgressCard.vue';
import UploadDialog from '../documents/UploadDialog.vue';
import type { Capabilities, DocumentSummary, UploadResult, Version } from '../types';

const props = defineProps<{ documentId: string; onDelete: () => Promise<void>; onInvalidate: (unavailable?: boolean) => void }>();
const auth = useSession(), documents = useDocumentOperations(), adminAccess = useAdminAccess();
const factsRevision = ref(0), factsKey = computed(() => `${props.documentId}/${factsRevision.value}`);
const query = useResource(signal => auth.session.json<DocumentSummary>(`/api/documents/${positiveId(props.documentId)}`, { signal }), factsKey);
const capabilities = useResource(signal => auth.session.json<Capabilities>(`/api/documents/${positiveId(props.documentId)}/capabilities`, { signal }), factsKey);
const favorites = useFavorites(() => query.data ? [props.documentId] : [], () => query.data);
const blocked = computed(() => favorites.unavailable(props.documentId));
const detail = computed(() => blocked.value ? undefined : query.data);
const selected = ref<string | null>(null), revision = ref(0), upload = ref(false), preview = ref<string | null>(null);
const busy = ref(false), error = ref(''), metadataTitle = ref<string | null>(null);
const versionStatus = shallowRef<{ key: string; value: ReviewStatus }>();
const deniedVersions = shallowRef<Set<string>>(new Set());
let lock = false, retryRequest: AbortController | null = null;
onScopeDispose(() => retryRequest?.abort());
watch([blocked, () => query.status, () => query.error, () => capabilities.status, () => capabilities.error, () => capabilities.data], () => {
  if (blocked.value || [401, 403, 404].includes(query.status ?? 0) || [401, 403, 404].includes(capabilities.status ?? 0)) {
    props.onInvalidate(true);
  } else if (query.error || capabilities.error || (capabilities.data && !canDelete(capabilities.data))) {
    props.onInvalidate();
  }
});
watch(() => props.documentId, () => {
  selected.value = null; preview.value = null; upload.value = false; error.value = '';
});
watch([() => query.error, () => query.status, () => capabilities.status, blocked], () => {
  if (query.error || blocked.value) {
    preview.value = null;
  }
  if (blocked.value || [401, 403, 404].includes(query.status ?? 0) || [401, 403, 404].includes(capabilities.status ?? 0)) {
    upload.value = false;
    selected.value = null;
  }
});
const versions = computed(() => (detail.value?.versions ?? []).filter(item => !deniedVersions.value.has(item.id)));
const version = computed(() => selected.value ? versions.value.find(item => item.id === selected.value) : versions.value.find(item => item.id === detail.value?.currentVersionId));
const versionId = computed(() => version.value?.id);
const path = computed(() => versionId.value ? `/api/documents/${props.documentId}/versions/${versionId.value}` : '');
const versionKey = computed(() => `${query.key}/${query.revision}/${versionId.value ?? ''}/${revision.value}`);
let liveVersion = versionKey.value;
watch(versionKey, key => { liveVersion = key; }, { flush: 'sync' });
// 每个子版本的回调绑定创建时的版本键；已经卸载的子树不能更新当前版本事实。
const receiveStatus = computed(() => {
  const key = versionKey.value;
  return (value: ReviewStatus | undefined) => {
    if (liveVersion === key) {
      versionStatus.value = value ? { key, value } : undefined;
    }
  };
});
const currentStatus = computed(() => versionStatus.value?.key === versionKey.value && versionStatus.value.value.state.documentId === props.documentId
  && versionStatus.value.value.state.versionId === versionId.value ? versionStatus.value.value : undefined);
const canRead = computed(() => currentStatus.value?.capabilities.canRead === true);
const factsLoading = computed(() => query.loading || capabilities.loading);
const canUploadVersion = computed(() => !!detail.value && capabilities.data?.canUploadVersion === true && !factsLoading.value);
function refreshFacts() {
  liveVersion = '';
  versionStatus.value = undefined; preview.value = null; metadataTitle.value = null;
  factsRevision.value++;
  props.onInvalidate();
}
function recheck() {
  if (!factsLoading.value && !documents.state.exclusive) {
    deniedVersions.value = new Set();
    refreshFacts();
  }
}
const downloadDenied = computed(() => {
  const key = versionKey.value, id = versionId.value;
  return () => {
    if (liveVersion === key && id) {
      selected.value = id;
      refreshFacts();
    }
  };
});
const loseVersion = computed(() => {
  const key = versionKey.value, id = versionId.value;
  return () => {
    if (liveVersion !== key) {
      return;
    }
    liveVersion = '';
    if (id) {
      deniedVersions.value = new Set([...deniedVersions.value, id]);
    }
    versionStatus.value = undefined; preview.value = null; selected.value = id ?? selected.value;
    query.reload(); capabilities.reload();
  };
});
watch([versionId, canRead], ([, read]) => {
  if (!read) {
    preview.value = null;
  }
});
async function retry() {
  if (lock || !versionId.value) {
    return;
  }
  const operations = documents.operations, operation = operations?.beginWrite('parse-retry');
  if (operations && !operation) {
    return;
  }
  const controller = new AbortController();
  retryRequest = controller;
  lock = true; busy.value = true; error.value = '';
  try {
    await auth.session.json(`/api/documents/${props.documentId}/retry?versionId=${versionId.value}`, { method: 'POST', signal: controller.signal });
    operation?.settle('confirmed');
    if (!controller.signal.aborted) {
      revision.value++;
      query.reload();
    }
  } catch (failure) {
    operation?.settle(operationFailure(failure));
    if (!controller.signal.aborted && !isAbort(failure)) {
      error.value = messageOf(failure);
      capabilities.reload();
    }
  } finally {
    lock = false;
    busy.value = false;
  }
}
function metadataDenied(status: number) {
  capabilities.reload();
  if (status === 404) {
    query.reload();
  }
}
function reviewChanged() {
  preview.value = null; query.reload(); capabilities.reload(); revision.value++;
}
function select(value: Version) {
  if (value.id !== versionId.value) {
    liveVersion = '';
    versionStatus.value = undefined; preview.value = null; selected.value = value.id;
  }
}
function uploaded(result: UploadResult) {
  selected.value = result.versionId;
  revision.value++;
  query.reload();
}
</script>

<template>
  <PageHeader :title="detail ? metadataTitle ?? detail.title : '文档详情'" description="版本、处理状态与原始文件"><template #extra><RouterLink to="/documents">返回文档中心</RouterLink></template></PageHeader>
  <ElButton :disabled="factsLoading || documents.state.exclusive" @click="recheck">重新核对操作资格</ElButton>
  <p v-if="factsLoading" role="status">正在核对文档与操作资格…</p>
  <Feedback :loading="query.loading" :error="query.error" :retry="recheck" />
  <KbAlert v-if="blocked" type="info" title="文档当前不可查看"><template #action><ElButton @click="recheck">重新读取</ElButton></template></KbAlert>
  <template v-if="detail">
    <section class="panel"><div class="toolbar"><ElTag type="info">密级 {{ detail.securityLevel }}</ElTag><span class="muted">当前发布：{{ detail.currentVersionNo == null ? '尚无版本' : `v${detail.currentVersionNo}` }}</span></div>
      <ElSpace wrap><FavoriteButton :id="documentId" :favorites="favorites" />
        <RouterLink v-if="adminAccess.canManageSpace(detail.spaceId) || adminAccess.has(perm.securityLevelWrite)" :to="`/admin/documents/${documentId}`">管理此文档</RouterLink>
        <ElButton :disabled="documents.state.exclusive || !canUploadVersion" @click="upload = true">上传新版本</ElButton>
        <ElButton :disabled="!versionId || !canRead" @click="preview = `${path}/preview`">预览选中版本</ElButton>
        <DownloadButton v-if="versionId" :key="versionKey" :path="`${path}/download`" :name="version?.fileName ?? detail.title" :disabled="!capabilities.data?.canDownload || !canRead || !currentStatus?.capabilities.canDownload" @denied="downloadDenied" />
        <ElButton :loading="busy" :disabled="documents.state.exclusive || !capabilities.data?.canRetry || !version || !retryable(version)" @click="retry">重试失败解析</ElButton>
        <ElButton type="danger" plain :disabled="documents.state.exclusive || !canDelete(capabilities.data)" @click="onDelete()">删除文档</ElButton>
      </ElSpace>
      <KbAlert v-if="capabilities.error" style="margin-top: 16px" type="warning" title="操作资格暂不可确认，相关操作已禁用。" />
      <KbAlert v-if="error" style="margin-top: 16px" type="error" :title="error" />
    </section>
    <MetadataPanel :document-id="documentId" :can-edit="capabilities.data?.canEditMetadata === true" @title="metadataTitle = $event" @denied="metadataDenied" />
    <template v-if="versionId">
      <VersionReviewPanel :key="`review:${versionKey}`" :document-id="documentId" :version-id="versionId" :files="false" @status="receiveStatus" @denied="loseVersion" @changed="reviewChanged" />
      <ProgressCard v-if="canRead" :key="`progress:${documentId}/${versionId}/${revision}`" :document-id="documentId" :version-id="versionId" :revision="revision" @denied="loseVersion" />
      <VersionComments :key="`${documentId}/${versionId}`" :document-id="documentId" :version-id="versionId" :status="currentStatus" />
    </template>
    <p v-else>默认读取当前发布版本；查看候选时请从可见版本列表明确选择。</p>
    <section class="panel"><h2>版本历史</h2><ElTable row-key="id" :data="versions">
      <ElTableColumn label="版本"><template #default="{ row }"><ElButton link type="primary" @click="select(row as Version)">v{{ row.versionNo }}{{ row.current ? ' · 当前发布' : '' }}</ElButton></template></ElTableColumn>
      <ElTableColumn label="文件名" prop="fileName" /><ElTableColumn label="处理状态"><template #default="{ row }">{{ progressLabel(row) }}</template></ElTableColumn>
    </ElTable></section>
  </template>
  <UploadDialog :open="upload" :document-id="documentId" :access="{ key: `${factsKey}/${query.revision}/${capabilities.revision}`, canUploadVersion, checking: factsLoading }" :on-recheck="recheck" @denied="refreshFacts" @close="upload = false" @uploaded="uploaded" />
  <VersionPreview :path="detail && canRead ? preview : null" :file-name="version?.fileName" @close="preview = null" @denied="loseVersion" />
</template>
