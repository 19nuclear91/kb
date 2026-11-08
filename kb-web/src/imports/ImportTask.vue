<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import KbAlert from '../ui/KbAlert.vue';
import { importApi, importMessage, type Batch, type Capabilities, type ImportItem } from './api';
import { ImportQueue } from './queue';
import { useImportQueue } from './useImportQueue';
import { FileHasher, matchFile } from './files';
import { ImportVerifier } from './verifier';
import ImportProgress from './ImportProgress.vue';

const props = defineProps<{ importId: string; initialFiles?: Map<string, File>; autoStart?: boolean; concurrency?: number }>();
const auth = useSession(), queue = new ImportQueue(auth.session, props.importId, props.concurrency ?? 2), state = useImportQueue(queue);
const life = new AbortController(), batch = shallowRef<Batch>(), cursor = ref<string | null>(null), loading = ref(false), error = ref('');
const capabilities = shallowRef<Capabilities>(), matching = ref(false), verifier = new ImportVerifier(queue);
let hasher = new FileHasher();
const unknown = computed(() => state.value.rows.filter(row => row.phase === 'unknown').length);
onBeforeUnmount(() => { life.abort(); verifier.dispose(); hasher.dispose(); props.initialFiles?.clear(); });
watch(() => state.value.active, (active, previous) => { if (active === 0 && previous > 0 && !life.signal.aborted) void load(); });
watch(() => state.value.denied, (denied, previous) => {
  if (denied) {
    hasher.dispose(); props.initialFiles?.clear();
    if (batch.value) batch.value = { ...batch.value, canContinue: false, spaceId: undefined, securityLevel: undefined };
  } else if (previous) hasher = new FileHasher();
}, { flush: 'sync' });
onMounted(async () => {
  matching.value = !!props.initialFiles?.size;
  await load();
  if (life.signal.aborted) return;
  const generation = queue.captureAccess();
  try {
    const caps = await importApi.capabilities(auth.session, life.signal);
    if (life.signal.aborted) return;
    capabilities.value = caps;
    queue.setConcurrency(caps.uploadConcurrency);
    if (props.initialFiles?.size) {
      let pages = 1;
      while (cursor.value && pages++ < 25 && !life.signal.aborted && !error.value) await load(true);
      for (const row of state.value.rows) {
        const file = row.item.file && props.initialFiles.get(row.item.file.itemKey);
        if (file && row.item.file && row.item.canUpload) { await matchFile(file, row.item.file, capabilities.value, hasher, life.signal); queue.attach(row.item.itemId, file, generation); }
      }
      props.initialFiles.clear(); if (queue.isAccessCurrent(generation) && props.autoStart && !error.value) queue.start();
    }
  } catch (failure) { if (!life.signal.aborted) error.value = importMessage(failure); }
  finally { if (!life.signal.aborted) matching.value = false; }
});
async function load(more = false) {
  if (loading.value) return;
  loading.value = true; error.value = '';
  const generation = queue.captureAccess();
  try {
    // 两个读取可观察到不同的权限快照；拒绝立即生效，恢复必须等两者都确认。
    const [summary, page] = await Promise.all([
      importApi.batch(auth.session, props.importId, life.signal).then(value => {
        if (!life.signal.aborted && !value.canContinue) queue.revoke();
        return value;
      }),
      importApi.items(auth.session, props.importId, more ? cursor.value : null, life.signal).then(value => {
        if (!life.signal.aborted && value.items.some(item => !item.file)) queue.revoke();
        return value;
      }),
    ]);
    if (life.signal.aborted) return;
    if (summary.canContinue && page.items.every(item => !!item.file)) queue.authorize(generation);
    queue.setItems(page.items, generation); cursor.value = page.nextCursor;
    batch.value = queue.isAccessCurrent(generation) && !state.value.denied ? summary
      : { ...summary, canContinue: false, spaceId: undefined, securityLevel: undefined };
  } catch (failure) { if (!life.signal.aborted) { error.value = importMessage(failure); batch.value = undefined; queue.revoke(); } }
  finally { if (!life.signal.aborted) loading.value = false; }
}
async function choose(event: Event, item: ImportItem) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; input.value = '';
  if (!file || !capabilities.value || matching.value || !item.file || !item.canUpload) return;
  matching.value = true; error.value = '';
  const generation = queue.captureAccess();
  try {
    const latest = await queue.check(item.itemId);
    if (!latest?.canUpload || !latest.file) return;
    await matchFile(file, latest.file, capabilities.value, hasher, life.signal);
    if (!life.signal.aborted) queue.attach(latest.itemId, file, generation);
  } catch (failure) { if (!life.signal.aborted) error.value = importMessage(failure); }
  finally { if (!life.signal.aborted) matching.value = false; }
}
async function check(id: string) {
  error.value = ''; try { await verifier.manual(id); await load(); }
  catch (failure) { if (!life.signal.aborted) error.value = importMessage(failure); }
}
</script>
<template>
  <section aria-label="固定导入任务">
    <RouterLink to="/imports">返回我的导入任务</RouterLink>
    <ElButton :disabled="loading" @click="load()">刷新当前任务</ElButton>
    <KbAlert v-if="error || state.notice" type="warning" :title="error || state.notice" />
    <p v-if="loading" role="status">正在核对持久任务…</p>
    <p v-if="matching" role="status">正在逐项匹配本地文件摘要…</p>
    <template v-if="batch"><h2>批次 {{ batch.importId }}</h2>
      <p>待上传 {{ batch.counts.pending }} / 处理中 {{ batch.counts.processing }} / 已受理 {{ batch.counts.accepted }} / 失败 {{ batch.counts.failed }}</p>
      <p>受理阶段：{{ batch.state }}。已受理不等于已解析或发布。</p>
      <p v-if="batch.canContinue">固定空间 {{ batch.spaceId }}，密级 {{ batch.securityLevel }}</p><p v-else>当前不可继续上传，仅显示本人最小历史。</p>
      <p>本页回执待确认 {{ unknown }} 项（独立于服务端计数）</p>
      <p>待确认项每 3 秒核对，连续 3 次失败或 3 分钟后停止；可主动刷新。重新选择文件不会自动发送。</p>
      <ElButton :disabled="loading || matching || state.running || state.active > 0 || !batch.canContinue" @click="queue.start()">主动继续已选文件</ElButton>
      <ElButton :disabled="!state.running" @click="queue.pause()">暂停后续上传</ElButton>
      <ul><li v-for="row in state.rows" :key="row.item.itemId">
        <h3>{{ row.item.ordinal }}. {{ row.item.file?.fileName ?? '文件描述当前不可见' }} · 项 {{ row.item.itemId }}</h3>
        <p v-if="row.item.file">{{ row.item.file.title }} · {{ row.item.file.sizeBytes }} bytes</p>
        <p>{{ row.item.state === 'ACCEPTED' ? '已受理' : row.phase === 'sending' ? '上传中' : row.phase === 'unknown' ? '待确认' : row.item.state }} {{ row.item.failureCode }} {{ row.message }}</p>
        <p v-if="row.item.receipt">文档 {{ row.item.receipt.documentId }} / 固定版本 {{ row.item.receipt.versionId }} / 解析任务 {{ row.item.receipt.parseTaskId }}</p>
        <ImportProgress v-if="row.item.receipt" :key="`${row.item.receipt.documentId}:${row.item.receipt.versionId}`" :receipt="row.item.receipt" />
        <template v-if="row.item.canUpload && row.item.file"><label>为第 {{ row.item.ordinal }} 项重新选择匹配文件 <input type="file" :aria-label="`第 ${row.item.ordinal} 项恢复文件`" :disabled="matching || loading || state.active > 0 || !capabilities" @change="choose($event, row.item)" /></label>
          <span v-if="row.selected">已匹配，等待主动继续</span>
          <ElButton :disabled="!row.selected || matching || loading || state.active > 0 || row.phase === 'unknown'" @click="queue.start([row.item.itemId])">继续第 {{ row.item.ordinal }} 项</ElButton>
        </template>
        <ElButton :disabled="row.phase === 'sending' || loading" @click="check(row.item.itemId)">核对第 {{ row.item.ordinal }} 项</ElButton>
      </li></ul>
      <ElButton v-if="cursor" :disabled="loading" @click="load(true)">更多文件项</ElButton><p v-else-if="state.rows.length && !loading">文件项已到末页</p>
    </template>
  </section>
</template>
