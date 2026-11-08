<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import { ElButton, ElPagination, ElTable, ElTableColumn, ElTag } from 'element-plus';
import { RouterLink } from 'vue-router';
import { useSession } from '../SessionProvider';
import MetadataFilters from '../documents/metadata/Filters.vue';
import { metadataApi, type Filters } from '../documents/metadata/api';
import { consumeDocumentDeleted } from '../documents/notice';
import { useResource } from '../useResource';
import Feedback from '../ui/Feedback.vue';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import { useFavorites } from '../favorites/useFavorites';
import FavoriteButton from '../favorites/FavoriteButton.vue';
import ProgressCard from '../documents/ProgressCard.vue';
import UploadDialog from '../documents/UploadDialog.vue';
import type { Space as KnowledgeSpace, UploadResult } from '../types';

const auth = useSession();
const deletionNotice = ref<number | null>(null);
watch([() => auth.session, () => auth.state.epoch], ([session, epoch], _, onCleanup) => {
  if (consumeDocumentDeleted(session)) {
    deletionNotice.value = epoch;
  }
  const timer = setTimeout(() => { deletionNotice.value = null; }, 5000);
  onCleanup(() => clearTimeout(timer));
}, { immediate: true });
const page = ref(1), filters = shallowRef<Filters>({});
const upload = ref(false), submitted = shallowRef<UploadResult | null>(null);
const query = useResource(signal => metadataApi.query(auth.session, filters.value, page.value, signal), () => `${page.value}/${JSON.stringify(filters.value)}`);
const spaces = useResource(signal => auth.session.json<KnowledgeSpace[]>('/api/admin/spaces', { signal }), 'spaces');
const favorites = useFavorites(() => query.data?.items.map(item => item.id) ?? [], () => query.data);
const items = computed(() => query.data?.items.filter(item => !favorites.unavailable(item.id)) ?? []);
function filter(value: Filters) {
  filters.value = value;
  page.value = 1;
}
function uploaded(result: UploadResult) {
  submitted.value = result;
  query.reload();
}
</script>

<template>
  <PageHeader title="文档中心" description="整理团队知识，在权限范围内发现可靠资料"><template #extra><RouterLink to="/imports">批量导入文件</RouterLink><ElButton type="primary" size="large" @click="upload = true">上传文档</ElButton></template></PageHeader>
  <KbAlert v-if="deletionNotice === auth.state.epoch" type="success" title="文档已删除" />
  <ProgressCard v-if="submitted && !favorites.unavailable(submitted.documentId)" :key="submitted.versionId" :document-id="submitted.documentId" :version-id="submitted.versionId" submitted />
  <section class="panel"><MetadataFilters :spaces="spaces.data ?? []" @submit="filter" />
    <KbAlert v-if="spaces.error" type="warning" title="空间目录暂不可用，仍可查看全部文档。" style="margin-bottom: 16px" />
    <Feedback :loading="query.loading" :error="query.error" :retry="query.reload" />
    <template v-if="query.data">
      <ElTable row-key="id" :data="items">
        <ElTableColumn label="文档"><template #default="{ row }"><RouterLink :to="`/documents/${row.id}`">{{ row.title }}</RouterLink></template></ElTableColumn>
        <ElTableColumn label="当前版本"><template #default="{ row }">{{ row.currentVersionNo == null ? '尚未发布' : `v${row.currentVersionNo}` }}</template></ElTableColumn>
        <ElTableColumn label="密级"><template #default="{ row }"><ElTag type="info">密级 {{ row.securityLevel }}</ElTag></template></ElTableColumn>
        <ElTableColumn label="最近更新"><template #default="{ row }">{{ row.updatedAt ? new Date(row.updatedAt).toLocaleDateString('zh-CN') : '—' }}</template></ElTableColumn>
        <ElTableColumn label="个人收藏"><template #default="{ row }"><FavoriteButton :id="row.id" :favorites="favorites" /></template></ElTableColumn>
      </ElTable>
      <ElPagination style="margin-top: 24px" :current-page="page" :total="Number(query.data.total)" :page-size="20" layout="prev, pager, next" @current-change="page = $event" />
    </template>
  </section>
  <UploadDialog :open="upload" @close="upload = false" @uploaded="uploaded" />
</template>
