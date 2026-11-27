<script setup lang="ts">
import { computed, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import KbAlert from '../ui/KbAlert.vue';
import { digest, digests } from './api';
import { usePage, useRead } from './hooks';
import DigestMembers from './DigestMembers.vue';
const props = defineProps<{ id?: string }>(), auth = useSession(), cursor = ref<string | null>(null), expanded = ref<string | null>(null);
const list = usePage((cursor, signal) => digests(auth.session, cursor, signal), 'digests');
const resource = useRead(computed(() => props.id ? (signal: AbortSignal) => digest(auth.session, props.id!, cursor.value, signal) : null), () => `${props.id}:${cursor.value}`);
function refresh() { expanded.value = null; cursor.value = null; resource.reload(); list.reload(); }
</script>
<template>
  <section class="panel" aria-label="知识周报"><ElButton @click="refresh">重新读取周报</ElButton>
    <p>北京时间周一 09:00 起生成上一周汇总，延迟处理仍归原统计周。取消后已生成的周报保留，条目和计数按当前权限重新计算。</p>
    <template v-if="id">
      <KbAlert v-if="resource.error" type="warning" :title="resource.error" /><p v-else-if="resource.loading">正在读取周报…</p>
      <template v-else-if="resource.data"><h2>{{ resource.data.digest.weekStart }} 至 {{ resource.data.digest.weekEnd }}（不含结束日）</h2>
        <ul><li v-for="group in resource.data.groups.items" :key="group.documentId">
          {{ group.title }} · 当前可读 {{ group.publicationCount }} 次发布
          <ElButton @click="expanded = expanded === group.documentId ? null : group.documentId">{{ expanded === group.documentId ? '收起' : '展开各次发布' }}</ElButton>
          <DigestMembers v-if="expanded === group.documentId" :id="id" :document-id="group.documentId" :key="`${id}:${group.documentId}`" />
        </li></ul><ElButton v-if="resource.data.groups.hasMore" @click="expanded = null; cursor = resource.data.groups.nextCursor">下一页文档组</ElButton>
      </template>
    </template>
    <template v-else><KbAlert v-if="list.error" type="warning" :title="list.error" /><p v-else-if="list.loading">正在读取周报列表…</p>
      <template v-else-if="list.data"><p v-if="!list.data.items.length">当前没有可见周报。没有可读更新时不会生成空提醒。</p>
        <ul><li v-for="item in list.data.items" :key="item.digestId"><RouterLink :to="`/digests/${item.digestId}`">{{ item.weekStart }} 至 {{ item.weekEnd }} 的周报</RouterLink></li></ul>
        <ElButton v-if="list.data.hasMore" @click="list.more">下一页周报</ElButton>
      </template>
    </template>
  </section>
</template>
