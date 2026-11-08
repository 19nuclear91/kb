<script setup lang="ts" generic="T = unknown">
import { computed } from 'vue';
import { ElButton } from 'element-plus';
import type { ApiError } from '../api';
import type { WriteIntent, adminWrite } from './api';
import { useAdminWrite, type WriteBindings, type WriteOutcome } from './hooks';
import AdminModal from './AdminModal.vue';
import WriteNotice from './WriteNotice.vue';
import { settle } from './ui';

const props = defineProps<{
  title: string; scope: string; build: (data: FormData) => WriteIntent; onDone: (result: T) => void;
  onRefresh: () => void; disabled?: boolean; execute?: typeof adminWrite; onVerify?: () => void;
  onRejected?: (error: ApiError) => void; retryDisabled?: boolean; bindings?: WriteBindings;
}>();
const emit = defineEmits<{ close: [] }>();
const write = useAdminWrite(() => props.scope, props.execute, props.bindings);
const retryDisabled = computed(() => props.retryDisabled ?? props.disabled ?? false);
function receive(outcome: WriteOutcome<T>) {
  if (outcome.status === 'rejected' && props.onRejected) {
    props.onRejected(outcome.error);
  } else {
    settle(outcome, props.onDone, props.onRefresh);
  }
}
function submit(event: Event) {
  if (!props.disabled && !write.pending && !write.blocked) {
    const data = new FormData(event.currentTarget as HTMLFormElement);
    void write.submit<T>(() => props.build(data)).then(receive);
  }
}
function retry() {
  if (!retryDisabled.value) {
    void write.retry<T>().then(receive);
  }
}
</script>

<template>
  <AdminModal :title="title" :write="write" @close="emit('close')"><WriteNotice :write="write" :disabled="retryDisabled" :on-retry="retry" :on-verify="onVerify ?? onRefresh" />
    <form @submit.prevent="submit"><fieldset :disabled="write.busy"><slot /><div style="margin-top: 16px"><ElButton type="primary" native-type="submit" :disabled="disabled || write.pending || write.blocked">确认提交</ElButton></div></fieldset></form>
  </AdminModal>
</template>
