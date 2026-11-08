<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElButton, ElSpace } from 'element-plus';
import { useAdminWrite } from '../admin/hooks';
import WriteNotice from '../admin/WriteNotice.vue';
import KbAlert from '../ui/KbAlert.vue';
import { COMMENT_MAX, commentLength, commentWrite, createIntent, type CommentReceipt } from './api';
import { composerScope, useThread } from './thread';

const props = defineProps<{ parent: string | null; label: string; hold: string }>();
const emit = defineEmits<{ settled: [] }>();
const thread = useThread();
const write = useAdminWrite(() => composerScope(thread.documentId, thread.versionId, props.parent), commentWrite, thread.writes);
const draft = ref(''), notice = ref('');
// 保持锁直到拒绝回调发起资格重核，避免 await 的微任务间隙短暂恢复提交。
const sending = ref(false);
const blocked = computed(() => props.hold !== '' || write.blocked);
const length = computed(() => commentLength(draft.value));
const valid = computed(() => length.value >= 1 && length.value <= COMMENT_MAX && !/\u0000/.test(draft.value));
function receive(result: Awaited<ReturnType<typeof write.submit<CommentReceipt>>>) {
  if (result.status === 'confirmed') {
    draft.value = '';
    notice.value = result.value.deleted ? '原评论已由本人删除，未重新发表。' : result.value.replayed ? '已核对为同一次提交，未重复发表。' : '已发表。';
    emit('settled');
  } else if (result.status === 'rejected') {
    notice.value = '';
    if (result.error.status === 404) {
      thread.deny();
    } else if (result.error.status === 403 || result.error.status === 409) {
      thread.recheck();
    }
  }
}
function submit() {
  if (!valid.value || sending.value || write.pending || blocked.value) {
    return;
  }
  notice.value = '';
  void dispatch(() => write.submit<CommentReceipt>(() => createIntent(thread.documentId, thread.versionId, props.parent, draft.value)));
}
function retry() {
  if (!blocked.value && !sending.value) {
    void dispatch(() => write.retry<CommentReceipt>());
  }
}
async function dispatch(request: () => ReturnType<typeof write.submit<CommentReceipt>>) {
  sending.value = true;
  try {
    receive(await request());
  } finally {
    sending.value = false;
  }
}
</script>

<template>
  <div class="comment-composer">
    <WriteNotice :write="write" :disabled="blocked" :on-retry="retry" :on-verify="() => emit('settled')" />
    <KbAlert v-if="notice" type="success" :title="notice" />
    <label>{{ label }}<textarea v-model="draft" :aria-label="label" :disabled="write.busy" @input="notice = ''" /></label>
    <ElSpace><span class="muted" aria-live="polite">{{ length }} / {{ COMMENT_MAX }}</span>
      <span v-if="length > COMMENT_MAX" role="alert">超过 {{ COMMENT_MAX }} 个字符，不会截断，请删减后提交。</span>
      <span v-if="blocked" role="status" class="muted">{{ hold || '正在确认删除文档，暂不能发表。' }}</span>
      <ElButton type="primary" :disabled="!valid || sending || write.pending || blocked" @click="submit">{{ parent === null ? '发表评论' : '提交回复' }}</ElButton>
    </ElSpace>
  </div>
</template>
