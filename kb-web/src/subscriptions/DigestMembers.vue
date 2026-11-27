<script setup lang="ts">
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import KbAlert from '../ui/KbAlert.vue';
import { members } from './api';
import { usePage } from './hooks';
import UpdateList from './UpdateList.vue';
const props = defineProps<{ id: string; documentId: string }>(), auth = useSession();
const list = usePage((cursor, signal) => members(auth.session, props.id, props.documentId, cursor, signal), () => `${props.id}:${props.documentId}`);
</script>
<template>
  <div aria-label="周报发布明细"><KbAlert v-if="list.error" type="warning" :title="list.error" /><p v-else-if="list.loading">正在读取各次发布…</p>
    <template v-else-if="list.data"><UpdateList :items="list.data.items" /><ElButton v-if="list.data.hasMore" @click="list.more">下一页发布</ElButton></template>
  </div>
</template>
