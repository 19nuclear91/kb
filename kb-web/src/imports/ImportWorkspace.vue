<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import type { QueryPage, UploadSpace } from '../types';
import KbAlert from '../ui/KbAlert.vue';
import ImportCreatePanel from './ImportCreatePanel.vue';
import { importApi, importMessage, type Batch, type Capabilities } from './api';

const auth = useSession(), life = new AbortController();
const batches = shallowRef<Batch[]>([]), next = ref<string | null>(null), listError = ref(''), loading = ref(false);
const capabilities = shallowRef<Capabilities>(), spaces = shallowRef<UploadSpace[]>([]), levels = ref<number[]>([]), spacesCursor = ref<string | null>(null), optionError = ref('');
onBeforeUnmount(() => life.abort());
onMounted(() => { void list(); void options(); });
async function list(more = false) {
  if (loading.value) return;
  loading.value = true; listError.value = '';
  try { const page = await importApi.list(auth.session, more ? next.value : null, life.signal);
    if (!life.signal.aborted) { batches.value = more ? [...batches.value, ...page.items] : page.items; next.value = page.nextCursor; }
  } catch (error) { if (!life.signal.aborted) { listError.value = importMessage(error); batches.value = []; next.value = null; } }
  finally { if (!life.signal.aborted) loading.value = false; }
}
async function options() {
  try {
    const [caps, page, dictionary] = await Promise.all([importApi.capabilities(auth.session, life.signal),
      auth.session.json<QueryPage<UploadSpace>>('/api/auth/upload-spaces', { signal: life.signal }),
      auth.session.json<number[]>('/api/auth/security-levels', { signal: life.signal })]);
    if (!life.signal.aborted) { capabilities.value = caps; spaces.value = page.items; spacesCursor.value = page.nextCursor; levels.value = dictionary; }
  } catch (error) { if (!life.signal.aborted) optionError.value = importMessage(error); }
}
async function moreSpaces() {
  if (!spacesCursor.value) return;
  try { const page = await auth.session.json<QueryPage<UploadSpace>>(`/api/auth/upload-spaces?cursor=${encodeURIComponent(spacesCursor.value)}`, { signal: life.signal });
    if (!life.signal.aborted) { spaces.value = [...spaces.value, ...page.items]; spacesCursor.value = page.nextCursor; }
  } catch (error) { if (!life.signal.aborted) optionError.value = importMessage(error); }
}
</script>
<template>
  <ImportCreatePanel v-if="capabilities" :capabilities="capabilities" :spaces="spaces" :levels="levels" :more-spaces="!!spacesCursor" @more="moreSpaces" />
  <KbAlert v-if="optionError" type="warning" :title="optionError" />
  <section aria-label="本人历史任务"><h2>历史导入任务</h2>
    <ElButton :disabled="loading" @click="list()">刷新任务列表</ElButton>
    <KbAlert v-if="listError" type="warning" :title="listError" />
    <p v-else-if="loading" role="status">正在读取任务…</p><p v-else-if="!batches.length">暂无导入任务</p>
    <ul><li v-for="batch in batches" :key="batch.importId"><RouterLink :to="`/imports/${batch.importId}`">导入任务 {{ batch.importId }}</RouterLink>
      <span> {{ batch.createdAt }} · 待上传 {{ batch.counts.pending }} / 处理中 {{ batch.counts.processing }} / 已受理 {{ batch.counts.accepted }} / 失败 {{ batch.counts.failed }}</span>
      <span v-if="!batch.canContinue"> 当前不可继续上传</span></li></ul>
    <ElButton v-if="next" :disabled="loading" @click="list(true)">更多导入任务</ElButton><p v-else-if="batches.length && !loading">已到末页</p>
  </section>
</template>
