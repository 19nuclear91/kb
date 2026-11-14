<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElButton } from 'element-plus';
import VersionReviewPanel from '../reviews/VersionReviewPanel.vue';
import CommentTargetPanel from '../comments/CommentTargetPanel.vue';
import KbAlert from '../ui/KbAlert.vue';
import type { ReviewStatus } from '../reviews/api';
import type { NotificationItem } from './api';

const props = defineProps<{ item: NotificationItem }>();
const emit = defineEmits<{ close: [] }>();
const status = shallowRef<ReviewStatus>(), denied = ref(false);
const target = computed(() => props.item.target);
const router = useRouter();
const documentTarget = computed(() => target.value.kind === 'KNOWLEDGE_GAP' ? null : target.value);
const matches = computed(() => documentTarget.value && status.value && status.value.state.documentId === documentTarget.value.documentId && status.value.state.versionId === documentTarget.value.versionId);
const drifted = computed(() => documentTarget.value?.kind === 'DOCUMENT_VERSION_REVIEW' && matches.value && (status.value!.state.reviewId ?? '').toLowerCase() !== documentTarget.value.reviewId);
watch(target, value => {
  if (value.kind === 'KNOWLEDGE_GAP') { status.value = undefined; denied.value = false; void router.push(`/knowledge-gaps/${value.gapId}`); }
}, { immediate: true });
function deny() {
  status.value = undefined;
  denied.value = true;
}
</script>

<template>
  <section class="panel" aria-label="通知目标">
    <p v-if="target.kind === 'KNOWLEDGE_GAP'">正在打开问题 {{ target.gapId }} 的受保护详情；不自动标记已读或确认解决。</p>
    <template v-else>
    <div class="toolbar"><span>文档 {{ target.documentId }} · 固定版本 {{ target.versionId }}</span><ElButton @click="emit('close')">关闭目标</ElButton></div>
    <template v-if="target.kind === 'DOCUMENT_VERSION_COMMENT'">
      <p class="muted">打开目标不会标记已读；评论按当前权限重新核对，不改为显示最新版本的讨论。</p>
      <CommentTargetPanel :key="`${target.documentId}/${target.versionId}/${target.commentId}`" :document-id="target.documentId" :version-id="target.versionId" :comment-id="target.commentId" />
    </template>
    <template v-else>
      <p class="muted">打开目标不会标记已读，也不会提交任何审核决定；内容和操作按当前权限重新核对。</p>
      <KbAlert v-if="denied" type="warning" title="目标当前不可访问，已清理旧内容。请核对通知或稍后重试。" />
      <template v-else>
        <KbAlert v-if="drifted" type="warning" title="该版本的审核轮次已变化，以下为当前状态，请核对后再操作。" />
        <VersionReviewPanel :key="`${target.documentId}/${target.versionId}`" :document-id="target.documentId" :version-id="target.versionId" :files="false" @status="status = $event" @denied="deny" />
      </template>
    </template>
    </template>
  </section>
</template>
