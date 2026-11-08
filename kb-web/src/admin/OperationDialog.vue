<script setup lang="ts">
import { ref, useId } from 'vue';
import { ElButton, ElInput, ElSpace } from 'element-plus';
import { messageOf } from '../api';
import type { WriteIntent } from './api';
import { useAdminWrite } from './hooks';
import { operationReason, operationsWrite } from './operationsApi';
import AdminModal from './AdminModal.vue';
import WriteNotice from './WriteNotice.vue';
import KbAlert from '../ui/KbAlert.vue';

const props = defineProps<{ title: string; scope: string; max: number; allowed: boolean; build: (reason: string) => WriteIntent }>();
const emit = defineEmits<{ done: [value: unknown]; close: []; refresh: [] }>();
const write = useAdminWrite(() => props.scope, operationsWrite), reason = ref(''), error = ref(''), inputId = useId();
async function finish(retry = false) {
  if (!retry && (!props.allowed || write.pending)) {
    return;
  }
  let intent: WriteIntent | undefined;
  if (!retry) {
    try {
      intent = props.build(operationReason(reason.value, props.max));
      error.value = '';
    } catch (failure) {
      error.value = messageOf(failure);
      return;
    }
  }
  const outcome = retry ? await write.retry() : await write.submit(intent!);
  if (outcome.status === 'confirmed') {
    emit('done', outcome.value);
  } else if (outcome.status === 'rejected' && [404, 409].includes(outcome.error.status)) {
    emit('refresh');
  }
}
</script>

<template>
  <AdminModal :title="title" :write="write" @close="emit('close')">
    <div class="muted" style="margin-bottom: 16px"><slot name="description" /></div>
    <WriteNotice :write="write" :on-retry="() => { void finish(true); }" :on-verify="() => emit('refresh')" />
    <KbAlert v-if="error" type="error" :title="error" />
    <form @submit.prevent="finish()">
      <slot :locked="write.pending" />
      <label :for="inputId">操作原因</label><ElInput :id="inputId" v-model="reason" aria-label="操作原因" type="textarea" :disabled="write.pending" :rows="3" placeholder="请说明操作原因，不要填写正文或凭据" style="margin-bottom: 12px" />
      <p class="muted">去除首尾空白后 1–{{ max }} 个字符。</p>
      <KbAlert v-if="!allowed && !write.pending" type="warning" title="当前事实或资格未确认，请重新读取后再确认操作。" />
      <ElSpace style="margin-top: 16px"><ElButton type="primary" native-type="submit" :disabled="!allowed || write.pending">确认提交</ElButton><ElButton :disabled="write.busy" @click="emit('close')">关闭</ElButton></ElSpace>
    </form>
  </AdminModal>
</template>
