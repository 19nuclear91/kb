<script setup lang="ts">
import { ref, shallowRef } from 'vue';
import { ElButton, ElEmpty } from 'element-plus';
import Feedback from '../ui/Feedback.vue';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import { reviewPaths, type ReviewItem, type ReviewPage } from './api';
import { reviewLabel } from './labels';
import VersionReviewPanel from './VersionReviewPanel.vue';

const props = defineProps<{ kind: 'mine' | 'pending' }>();
const auth = useSession(), cursor = ref<string | null>(null), selected = shallowRef<ReviewItem | null>(null);
const query = useResource(signal => auth.session.json<ReviewPage>(reviewPaths.list(props.kind, cursor.value), { signal }), () => `${props.kind}/${cursor.value}`);
function refresh() {
  selected.value = null;
  query.reload();
}
function page(value: string | null) {
  selected.value = null;
  cursor.value = value;
}
</script>

<template>
  <section class="panel"><h2>{{ kind === 'mine' ? '本人上传或送审版本' : '当前合格审核待办' }}</h2>
    <ElButton @click="refresh">刷新列表</ElButton><Feedback :loading="query.loading" :error="query.error" :retry="query.reload" />
    <ElEmpty v-if="query.data?.items.length === 0" description="当前无可展示版本" />
    <ul><li v-for="item in query.data?.items" :key="item.state.versionId">
      <span v-if="kind === 'pending' && item.title">{{ item.title }} · </span>
      <span>文档 {{ item.state.documentId }} · v{{ item.state.versionNo }} · {{ reviewLabel(item.state) }}</span>
      <ElButton @click="selected = item">查看版本 {{ item.state.versionId }} 状态</ElButton>
    </li></ul>
    <ElButton v-if="query.data?.hasMore && query.data.nextCursor" @click="page(query.data.nextCursor)">下一页版本</ElButton>
    <ElButton v-if="cursor" @click="page(null)">回到第一页</ElButton>
  </section>
  <VersionReviewPanel v-if="selected" :key="`${selected.state.documentId}/${selected.state.versionId}`" :document-id="selected.state.documentId" :version-id="selected.state.versionId" @changed="query.reload" />
</template>
