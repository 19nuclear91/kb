<script setup lang="ts">
import { computed, onScopeDispose, ref, shallowRef } from 'vue';
import { ElButton, ElEmpty, ElTable, ElTableColumn, ElTag } from 'element-plus';
import { RouterLink } from 'vue-router';
import { useSession } from '../SessionProvider';
import { isAbort, messageOf } from '../api';
import KbAlert from '../ui/KbAlert.vue';
import { favoritePage, type FavoritePage } from './api';
import FavoriteButton from './FavoriteButton.vue';
import { useFavorites } from './useFavorites';

const props = defineProps<{ first: FavoritePage; refresh: () => void }>();
const auth = useSession();
const items = shallowRef(props.first.items), cursor = ref(props.first.nextCursor);
const busy = ref(false), error = ref('');
let lock = false;
const controller = new AbortController();
onScopeDispose(() => controller.abort());
const favorites = useFavorites(() => items.value.map(item => item.documentId), () => props.first, true);
const visible = computed(() => items.value.filter(item => {
  const status = favorites.status(item.documentId);
  return status.phase !== 'unavailable' && !(status.phase === 'ready' && status.favorited === false);
}));
async function more() {
  if (lock) {
    return;
  }
  lock = true;
  busy.value = true;
  error.value = '';
  try {
    const page = await favoritePage(auth.session, cursor.value, controller.signal);
    if (!controller.signal.aborted) {
      items.value = [...new Map([...items.value, ...page.items].map(item => [item.documentId, item])).values()];
      cursor.value = page.nextCursor;
    }
  } catch (failure) {
    if (!controller.signal.aborted && !isAbort(failure)) {
      error.value = messageOf(failure);
    }
  } finally {
    lock = false;
    if (!controller.signal.aborted) {
      busy.value = false;
    }
  }
}
</script>

<template>
  <section class="panel"><div class="toolbar"><ElButton @click="refresh">刷新收藏</ElButton>
    <span class="muted">按收藏顺序展示 · 每次读取重新核对文档权限</span></div>
    <ElTable v-if="visible.length" row-key="documentId" :data="visible">
      <ElTableColumn label="文档"><template #default="{ row }"><RouterLink :to="`/documents/${row.documentId}`">{{ row.title }}</RouterLink></template></ElTableColumn>
      <ElTableColumn label="当前版本"><template #default="{ row }">{{ row.currentVersionNo == null ? '尚未发布' : `v${row.currentVersionNo}` }}</template></ElTableColumn>
      <ElTableColumn label="密级"><template #default="{ row }"><ElTag type="info">密级 {{ row.securityLevel }}</ElTag></template></ElTableColumn>
      <ElTableColumn label="收藏时间"><template #default="{ row }">{{ new Date(row.favoritedAt).toLocaleString('zh-CN') }}</template></ElTableColumn>
      <ElTableColumn label="操作"><template #default="{ row }"><FavoriteButton :id="row.documentId" :favorites="favorites" /></template></ElTableColumn>
    </ElTable>
    <ElEmpty v-else description="暂无可查看的收藏" />
    <KbAlert v-if="error" style="margin-top: 16px" type="warning" :title="error" />
    <ElButton v-if="cursor" style="margin-top: 16px" aria-label="更多收藏" :loading="busy" :disabled="busy" @click="more">更多收藏</ElButton>
  </section>
</template>
