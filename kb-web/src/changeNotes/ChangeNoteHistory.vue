<script setup lang="ts">
import { useSession } from '../SessionProvider';
import { useCursorList } from '../admin/hooks';
import { changeNotesApi, type HistoryItem } from './api';

const props = defineProps<{ documentId: string; versionId: string }>();
const auth = useSession();
const list = useCursorList<HistoryItem>((cursor, signal) => changeNotesApi.history(auth.session, props, cursor, signal),
  () => `change-note-history:${props.documentId}/${props.versionId}`);
</script>
<template>
  <section aria-label="变更说明修改历史"><h3>变更说明修改历史</h3>
    <p v-if="list.error" role="alert">修改历史暂不可确认</p><p v-if="list.loading" role="status">正在读取修改历史…</p>
    <p v-if="!list.loading && !list.error && !list.items.length">尚无修改历史</p>
    <article v-for="item in list.items" :key="item.id"><p>{{ item.changedAt }} · {{ item.visibleToReaders ? '发布后写入' : '待审时写入' }}</p>
      <p>基准：{{ item.baseBefore ?? '无' }} → {{ item.baseAfter ?? '无' }}</p>
      <pre>{{ item.textBefore ?? '没有说明' }}</pre><span>→</span><pre>{{ item.textAfter ?? '没有说明' }}</pre>
      <p v-if="item.reason">更正原因：{{ item.reason }}</p>
    </article>
    <button v-if="list.hasMore" :disabled="list.loading" @click="list.more">继续读取修改历史</button>
    <button v-if="list.error" @click="list.reload">重新读取修改历史</button>
  </section>
</template>
