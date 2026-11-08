<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElButton, ElSpace } from 'element-plus';
import { useSession } from './SessionProvider';
import { ApiError, isAbort, messageOf } from './api';
import { readFile } from './FileAccess';

const props = defineProps<{ path: string; name: string; disabled?: boolean }>();
const emit = defineEmits<{ denied: [] }>();
const auth = useSession();
const error = ref(''), busy = ref(false);
let active: AbortController | null = null;
watch([() => props.path, () => auth.session], (_, __, onCleanup) => {
  error.value = '';
  busy.value = false;
  onCleanup(() => {
    active?.abort();
    active = null;
  });
}, { immediate: true });
async function download() {
  if (props.disabled || active) {
    return;
  }
  const controller = new AbortController(), name = props.name;
  active = controller;
  busy.value = true;
  error.value = '';
  try {
    const blob = await readFile(auth.session, props.path, controller.signal);
    const url = URL.createObjectURL(blob), link = document.createElement('a');
    try {
      link.href = url;
      link.download = name;
      link.rel = 'noopener';
      link.click();
    } finally {
      URL.revokeObjectURL(url);
    }
  } catch (failure) {
    if (active === controller && !controller.signal.aborted && !isAbort(failure)) {
      error.value = messageOf(failure);
      if (failure instanceof ApiError && [403, 404].includes(failure.status)) {
        emit('denied');
      }
    }
  } finally {
    if (active === controller) {
      active = null;
      if (!controller.signal.aborted) {
        busy.value = false;
      }
    }
  }
}
</script>

<template>
  <ElSpace direction="vertical"><ElButton :disabled="disabled" :loading="busy" @click="download">下载原件</ElButton><span v-if="error" role="alert" class="muted">{{ error }}</span></ElSpace>
</template>
