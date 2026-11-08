<script setup lang="ts">
import { ref } from 'vue';
import { ElButton } from 'element-plus';
import { useAdminWrite, type WriteOutcome } from '../admin/hooks';
import { settle } from '../admin/ui';
import AdminModal from '../admin/AdminModal.vue';
import WriteNotice from '../admin/WriteNotice.vue';
import KbAlert from '../ui/KbAlert.vue';
import { policyIntent, reviewWrite, type Policy, type PublicationPolicy } from './api';

const props = defineProps<{ value: PublicationPolicy; disabled: boolean; refresh: () => void }>();
const emit = defineEmits<{ close: []; done: [] }>();
const policy = ref<Policy>(props.value.policy);
const write = useAdminWrite(() => `publication-policy:${props.value.spaceId}`, reviewWrite);
const receive = (outcome: WriteOutcome<unknown>) => settle(outcome, () => emit('done'), props.refresh);
function retry() {
  if (!props.disabled) {
    void write.retry().then(receive);
  }
}
function verify() {
  write.reset();
  props.refresh();
}
const submit = () => { void write.submit(() => policyIntent(props.value.spaceId, policy.value, props.value.version)).then(receive); };
</script>

<template>
  <AdminModal title="确认发布策略" :write="write" @close="emit('close')">
    <WriteNotice :write="write" :on-retry="retry" :on-verify="verify" />
    <p>只影响新版本。已创建版本仍遵守原策略，关闭审核不会自动放行旧待审稿。</p>
    <label>发布策略<select v-model="policy" aria-label="发布策略" :disabled="write.pending"><option value="AUTO">自动发布</option><option value="REVIEW_REQUIRED">需审核后发布</option></select></label>
    <KbAlert type="info" title="管理资格不授予阅读或审核；本操作不会为任何人增加 review 授权。" />
    <ElButton type="primary" :disabled="disabled || write.pending" @click="submit">确认修改策略</ElButton>
  </AdminModal>
</template>
