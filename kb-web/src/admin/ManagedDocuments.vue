<script setup lang="ts">
import { ElButton } from 'element-plus';
import { RouterLink } from 'vue-router';
import { useSession } from '../SessionProvider';
import type { QueryPage } from '../types';
import { useCursorList } from './hooks';
import { spacePaths, type ManagedDocument } from './spaceApi';
import ResourceStatus from './ResourceStatus.vue';

const props = defineProps<{ id: string }>();
const auth = useSession();
const list = useCursorList<ManagedDocument>((cursor, signal) => auth.session.json<QueryPage<ManagedDocument>>(spacePaths.documents({ spaceId: props.id, cursor }), { signal }), () => `managed-documents:${props.id}`);
</script>

<template><section class="panel"><h2>空间内文档</h2><ResourceStatus :loading="list.loading" :error="list.error" :retry="list.reload" />
  <ul class="admin-list"><li v-for="item in list.items" :key="item.documentId"><RouterLink :to="`/admin/documents/${item.documentId}`">文档 {{ item.documentId }}</RouterLink><span>{{ item.spaceArchived ? '空间已归档' : '空间未归档' }}</span></li></ul>
  <p v-if="!list.loading && !list.error && !list.items.length">暂无文档</p><ElButton v-if="list.hasMore" :disabled="list.loading" @click="list.more">继续读取文档</ElButton><p class="muted">管理清单不提供阅读、预览或下载资格。</p>
</section></template>
