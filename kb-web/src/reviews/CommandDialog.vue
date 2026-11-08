<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElButton, ElSpace } from 'element-plus';
import { useAdminWrite } from '../admin/hooks';
import AdminModal from '../admin/AdminModal.vue';
import KbAlert from '../ui/KbAlert.vue';
import { reviewIntent, reviewWrite, type ReviewCommand, type ReviewReceipt, type ReviewState, type ReviewStatus } from './api';
import { commands } from './labels';

const props = defineProps<{ command: ReviewCommand; target: ReviewState; current: ReviewStatus | undefined; close: () => void; refresh: () => void; done: (receipt: ReviewReceipt) => void }>();
const write = useAdminWrite(() => `${props.target.documentId}/${props.target.versionId}/${props.target.reviewId ?? 'draft'}/${props.command}`, reviewWrite);
const reason = ref(''), conflict = ref(false);
const spec = computed(() => commands.find(([value]) => value === props.command)!);
const allowed = computed(() => {
  const current = props.current, target = props.target;
  return !!current && current.capabilities[spec.value[1]] === true
    && current.state.documentId === target.documentId && current.state.versionId === target.versionId
    && (current.state.reviewId ?? null) === (target.reviewId ?? null) && (current.state.roundNo ?? null) === (target.roundNo ?? null)
    && String(current.state.stateVersion) === String(target.stateVersion);
});
const invalidated = computed(() => !!props.current && !allowed.value);
watch(invalidated, invalid => {
  if (invalid) {
    conflict.value = true;
  }
}, { immediate: true });
const disabled = computed(() => !allowed.value || conflict.value || write.blocked);
function receive(result: Awaited<ReturnType<typeof write.submit<ReviewReceipt>>>) {
  if (result.status === 'confirmed') {
    props.done(result.value);
  } else if (result.status === 'rejected' && [403, 404, 409].includes(result.error.status)) {
    conflict.value = true;
    props.refresh();
  }
}
function verify() {
  write.reset();
  conflict.value = true;
  props.refresh();
}
function submit() {
  if (!disabled.value && !write.pending) {
    void write.submit<ReviewReceipt>(() => reviewIntent(props.command, props.target, reason.value)).then(receive);
  }
}
const retry = () => { void write.retry<ReviewReceipt>().then(receive); };
</script>

<template>
  <AdminModal :title="`确认${spec[2]}`" :write="write" @close="close">
    <p>文档 {{ target.documentId }} · v{{ target.versionNo }} · 轮次 {{ target.roundNo ?? '尚未送审' }}</p>
    <KbAlert v-if="command === 'approve'" type="warning" title="通过即发布，并替换当前发布版本。" />
    <KbAlert v-if="command === 'refresh'" type="warning" title="将重新解析候选并取消旧活动审核；完成后必须重新送审。" />
    <KbAlert v-if="write.message" :type="write.phase === 'uncertain' ? 'warning' : 'error'" :title="write.message" />
    <ElSpace v-if="write.phase === 'uncertain'"><ElButton :disabled="write.blocked" @click="retry">按原内容重试</ElButton><ElButton @click="verify">重新读取核对</ElButton></ElSpace>
    <KbAlert v-if="conflict || invalidated" type="info" :title="`当前操作资格或版本状态已变化；本窗口不会把决定应用到新状态或新轮次。${write.phase === 'uncertain' ? '仅可按原内容重试或重新读取核对。' : '请关闭后重新选择操作。'}`" />
    <label v-if="command === 'reject'">私有驳回原因<textarea v-model="reason" aria-label="私有驳回原因" :disabled="disabled || write.pending" /></label>
    <p>取消浏览器请求或关闭页面不会撤销服务器已执行的操作。</p>
    <ElButton type="primary" :disabled="disabled || write.pending || (command === 'reject' && !reason.trim())" @click="submit">确认{{ spec[2] }}</ElButton>
  </AdminModal>
</template>
