<script setup lang="ts">
import { computed } from 'vue';
import type { ApiError } from '../api';
import type { AdminWrite, WriteOutcome } from '../admin/hooks';
import WriteNotice from '../admin/WriteNotice.vue';
import { useDocumentOperations } from '../documents/OperationContext';
import { changeNoteIntent, type Note, type Receipt } from './api';
import type { ChangeNoteForm } from './useChangeNoteDraft';

const props = defineProps<{ data: Note; form: ChangeNoteForm; write: AdminWrite }>();
const emit = defineEmits<{ saved: []; rejected: [error: ApiError]; verify: []; close: [] }>();
const documents = useDocumentOperations();
const allowed = computed(() => props.data.editable && !props.data.reviewFrozen && !!props.data.writeBaseVersionId && !documents.state.exclusive);
const canSubmit = computed(() => allowed.value && !props.write.pending && !documents.state.validityUncertain);
function receive(result: WriteOutcome<Receipt>) {
  if (result.status === 'confirmed') emit('saved');
  else if (result.status === 'rejected') emit('rejected', result.error);
}
function submit(clear = false) {
  if (!canSubmit.value || clear && props.data.visibleToReaders) return;
  void props.write.submit<Receipt>(() => changeNoteIntent(props.data, clear ? null : props.form.text,
    props.data.visibleToReaders && props.data.hasNote ? props.form.reason : null)).then(receive);
}
function retry() { if (allowed.value) void props.write.retry<Receipt>().then(receive); }
</script>
<template>
  <section aria-label="编辑变更说明"><p>保存后相对 v{{ data.writeBaseVersionNo }}。修改历史对可读此版本的用户可见。</p>
    <p v-if="!data.visibleToReaders">待审说明可修改或清除，不要求原因；审核期间冻结。</p>
    <p v-else-if="!data.hasNote">这是发布后首次补写，不要求原因，将标记为“发布后补写”。</p>
    <p v-else>修改立即生效，必须填写原因；原因对可读此版本的用户可见，且不能清除说明。</p>
    <WriteNotice :write="write" :on-retry="retry" :on-verify="() => emit('verify')" :disabled="!allowed || write.busy" />
    <form @submit.prevent="submit()"><fieldset :disabled="!canSubmit">
      <label>变更说明<textarea v-model="form.text" aria-label="变更说明正文" required /></label>
      <label v-if="data.visibleToReaders && data.hasNote">更正原因<textarea v-model="form.reason" aria-label="变更说明更正原因" required /></label>
      <button type="submit" :disabled="!canSubmit">保存变更说明</button>
      <button v-if="!data.visibleToReaders && data.hasNote" type="button" :disabled="!canSubmit" @click="submit(true)">清除变更说明</button>
    </fieldset></form><button :disabled="write.busy" @click="emit('close')">关闭说明编辑</button>
  </section>
</template>
