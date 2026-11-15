<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElButton } from 'element-plus';
import { useAdminWrite, type AdminWrite, type WriteOutcome } from '../admin/hooks';
import { useDocumentOperations } from '../documents/OperationContext';
import WriteNotice from '../admin/WriteNotice.vue';
import { validityWrite, validityWrites, type Applicability, type Receipt, type UntilKind } from './api';
import type { ApiError } from '../api';

const props = defineProps<{ data: Applicability; write?: AdminWrite }>();
const emit = defineEmits<{ saved: []; rejected: [error: ApiError]; verify: []; close: [] }>();
const documents = useDocumentOperations(), write = props.write ?? useAdminWrite(() => `applicability:${props.data.documentId}/${props.data.versionId}`, validityWrite);
const fromKind = ref(props.data.effectiveFrom === null ? 'UNDECLARED' : 'DATE'), from = ref(props.data.effectiveFrom ?? '');
const untilKind = ref<UntilKind>(props.data.untilKind), until = ref(props.data.effectiveUntil ?? ''), reason = ref('');
const allowed = computed(() => props.data.canEdit && !props.data.reviewFrozen && !documents.state.exclusive);
const canSubmit = computed(() => allowed.value && !write.pending && !documents.state.validityUncertain);
function receive(result: WriteOutcome<Receipt>) {
  if (result.status === 'confirmed') emit('saved');
  else if (result.status === 'rejected') emit('rejected', result.error);
}
function submit() {
  if (!canSubmit.value) return;
  void write.submit<Receipt>(() => validityWrites.applicability(props.data, { effectiveFrom: fromKind.value === 'DATE' ? from.value : null,
    untilKind: untilKind.value, effectiveUntil: untilKind.value === 'DATE' ? until.value : null,
    reason: props.data.visibleToReaders ? reason.value : null, expectedApplicabilityVersion: props.data.applicabilityVersion })).then(receive);
}
const retry = () => { if (allowed.value) void write.retry<Receipt>().then(receive); };
</script>
<template>
  <div aria-label="编辑版本适用期"><p v-if="data.visibleToReaders">此版本已对读者可见，修改立即生效；更正原因对所有可读此版本的用户可见，请勿填写内部敏感信息。</p><p v-else>候选版本的声明不要求原因；审核期间不可修改。</p>
    <WriteNotice :write="write" :on-retry="retry" :on-verify="() => emit('verify')" :disabled="!allowed || write.busy" />
    <form @submit.prevent="submit"><fieldset :disabled="!canSubmit">
      <label>生效类型<select v-model="fromKind" aria-label="生效类型"><option value="UNDECLARED">未声明</option><option value="DATE">日期</option></select></label>
      <label v-if="fromKind === 'DATE'">生效日<input v-model="from" type="date" min="1900-01-01" max="2999-12-31" aria-label="生效日" required /></label>
      <label>截止类型<select v-model="untilKind" aria-label="截止类型"><option value="UNDECLARED">未声明</option><option value="OPEN_ENDED">明确长期有效</option><option value="DATE">日期</option></select></label>
      <label v-if="untilKind === 'DATE'">截止日<input v-model="until" type="date" min="1900-01-01" max="2999-12-31" aria-label="截止日" required /></label>
      <label v-if="data.visibleToReaders">更正原因<textarea v-model="reason" aria-label="更正原因" required /></label>
      <ElButton native-type="submit" type="primary" :disabled="!canSubmit">保存适用期</ElButton>
    </fieldset></form><ElButton :disabled="write.busy" @click="emit('close')">关闭编辑</ElButton>
  </div>
</template>
