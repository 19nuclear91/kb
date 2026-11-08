<script setup lang="ts">
import { ref } from 'vue';
import { ElButton } from 'element-plus';
import { useAdminWrite } from '../admin/hooks';
import AdminModal from '../admin/AdminModal.vue';
import WriteNotice from '../admin/WriteNotice.vue';
import KbAlert from '../ui/KbAlert.vue';
import { commentWrite, deleteIntent } from './api';
import { useThread } from './thread';

const props = defineProps<{ id: string }>();
const emit = defineEmits<{ close: []; done: [] }>();
const thread = useThread();
const write = useAdminWrite(() => `comment-delete:${thread.documentId}/${thread.versionId}/${props.id}`, commentWrite, thread.writes);
const missing = ref(false);
function receive(result: Awaited<ReturnType<typeof write.submit<void>>>) {
  if (result.status === 'confirmed') {
    emit('done');
  } else if (result.status === 'rejected' && result.error.status === 404) {
    missing.value = true;
  }
}
const retry = () => { void write.retry<void>().then(receive); };
const submit = () => { void write.submit<void>(() => deleteIntent(thread.documentId, thread.versionId, props.id)).then(receive); };
</script>

<template>
  <AdminModal title="删除本人评论" :write="write" @close="emit('close')">
    <p>删除后正文立即清除，无法恢复；他人的回复不受影响。</p>
    <WriteNotice :write="write" :disabled="write.blocked" :on-retry="retry" />
    <KbAlert v-if="missing" type="info" title="该评论已不存在或不属于当前账号。" />
    <ElButton type="danger" :disabled="write.pending || missing || write.blocked" @click="submit">确认删除</ElButton>
  </AdminModal>
</template>
