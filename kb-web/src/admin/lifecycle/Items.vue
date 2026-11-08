<script setup lang="ts">
import { ElButton, ElTable, ElTableColumn } from 'element-plus';
import Feedback from '../../ui/Feedback.vue';
import { useSession } from '../../SessionProvider';
import { useCursorList } from '../hooks';
import Note from '../Note.vue';
import { lifecycleItems } from './api';
import { labels } from './labels';

const props = defineProps<{ eventId: string }>();
const auth = useSession();
const list = useCursorList((cursor, signal) => lifecycleItems(auth.session, props.eventId, cursor, signal), () => `lifecycle-items:${props.eventId}`);
</script>

<template>
  <Feedback :loading="list.loading" :error="list.error" :retry="list.reload" />
  <ElTable row-key="id" size="small" :data="list.items"><ElTableColumn label="项编号" prop="id" /><ElTableColumn label="内部账号"><template #default="{ row }">{{ row.userId ?? '尚未匹配' }}</template></ElTableColumn>
    <ElTableColumn label="处置状态"><template #default="{ row }">{{ labels[row.status] }}</template></ElTableColumn><ElTableColumn label="尝试次数" prop="attempts" />
    <ElTableColumn label="核对情况"><template #default="{ row }">{{ row.resolvedAt ? '已核对处理' : '以当前状态为准' }}</template></ElTableColumn><ElTableColumn label="更新时间" prop="updatedAt" />
  </ElTable><Note>不展示原始员工标识或事件载荷。已停权记录是历史处置，当前账号状态需在账号详情核对。</Note><ElButton v-if="list.hasMore" :loading="list.loading" @click="list.more">更多员工项</ElButton>
</template>
