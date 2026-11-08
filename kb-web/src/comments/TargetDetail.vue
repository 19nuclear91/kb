<script setup lang="ts">
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import KbAlert from '../ui/KbAlert.vue';
import { commentDetail } from './api';
import { DENIED, failureText, when } from './thread';

const props = defineProps<{ documentId: string; versionId: string; commentId: string; thread: boolean }>();
const emit = defineEmits<{ toggle: [] }>();
const auth = useSession();
const query = useResource(signal => commentDetail(auth.session, props.documentId, props.versionId, props.commentId, signal), () => `${auth.state.epoch}:${props.documentId}/${props.versionId}/${props.commentId}`);
</script>

<template>
  <p v-if="query.loading" role="status" class="muted">正在核对评论目标…</p>
  <KbAlert v-else-if="query.status && DENIED.has(query.status)" type="warning" title="评论目标当前不可访问，已清理旧内容；不会改为显示其他版本的讨论。" />
  <KbAlert v-else-if="query.error || !query.data" type="error" :title="failureText(query.status)">
    <template #action><ElButton @click="query.reload">重新核对目标</ElButton></template>
  </KbAlert>
  <template v-else>
    <p>固定版本 {{ versionId }} · {{ query.data.item.parentId ? `回复（所属根评论 ${query.data.item.parentId.slice(0, 8)}）` : '根评论' }}</p>
    <p v-if="query.data.item.deleted" class="muted">该评论已删除。</p>
    <template v-else>
      <div class="comment-meta"><strong>{{ query.data.item.author!.label }}</strong><span class="muted">{{ when(query.data.item.createdAt) }}</span></div>
      <pre class="content-text">{{ query.data.item.body }}</pre>
    </template>
    <p v-if="!query.data.canReply" class="muted">当前不能在该讨论下追加回复。</p>
    <ElButton @click="emit('toggle')">{{ thread ? '收起本版本讨论' : '查看本版本讨论' }}</ElButton>
  </template>
</template>
