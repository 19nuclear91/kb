<script setup lang="ts">
import { reactive, ref } from 'vue';
import { ElButton, ElForm, type FormInstance } from 'element-plus';
import type { WriteIntent } from '../api';
import AdminModal from '../AdminModal.vue';
import WriteNotice from '../WriteNotice.vue';
import { useLifecycleWrite } from './useLifecycleWrite';
import ReasonField from './ReasonField.vue';

const props = defineProps<{ title: string; scope: string; description: string; build: (reason: string) => WriteIntent; result: string; onRefresh: () => void }>();
const emit = defineEmits<{ close: []; done: [message: string] }>();
const { write, submit, retry } = useLifecycleWrite(props.scope, { close: () => emit('close'), done: value => emit('done', value), refresh: () => props.onRefresh() }, () => props.result);
const values = reactive({ reason: '' }), form = ref<FormInstance>();
let lock = false;
async function finish() {
  if (lock || write.pending) {
    return;
  }
  lock = true;
  try {
    if (await form.value?.validate().catch(() => false)) {
      await submit(() => props.build(values.reason));
    }
  } finally {
    lock = false;
  }
}
</script>

<template>
  <AdminModal :title="title" :write="write" @close="emit('close')"><p>{{ description }}</p><WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
    <ElForm ref="form" :model="values" label-position="top" :disabled="write.pending" @submit.prevent="finish"><ReasonField v-model="values.reason" /><ElButton type="primary" native-type="submit" :disabled="write.pending">确认操作</ElButton></ElForm>
  </AdminModal>
</template>
