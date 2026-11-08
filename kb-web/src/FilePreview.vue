<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import { useSession } from './SessionProvider';
import { ApiError, isAbort, messageOf } from './api';
import { readFile } from './FileAccess';
import SafeText from './ui/SafeText.vue';
import KbAlert from './ui/KbAlert.vue';
import KbDrawer from './ui/KbDrawer.vue';
import KbSpin from './ui/KbSpin.vue';

const props = defineProps<{ path: string | null; onUnsupported?: () => void }>();
const emit = defineEmits<{ close: []; denied: [] }>();
const auth = useSession();
const file = shallowRef<{ path: string; url?: string; text?: string; error?: string }>({ path: '' });
watch([() => props.path, () => auth.session], ([path, session], _, onCleanup) => {
  if (!path) {
    file.value = { path: '' };
    return;
  }
  const controller = new AbortController();
  let url: string | undefined;
  file.value = { path };
  void readFile(session, path, controller.signal).then(async blob => {
    if (blob.type.startsWith('text/')) {
      const text = await blob.text();
      if (!controller.signal.aborted) {
        file.value = { path, text };
      }
    } else if (!controller.signal.aborted) {
      url = URL.createObjectURL(blob);
      file.value = { path, url };
    }
  }).catch(error => {
    if (!controller.signal.aborted && !isAbort(error)) {
      if (error instanceof ApiError && error.status === 415 && props.onUnsupported) {
        props.onUnsupported();
        return;
      }
      file.value = { path, error: messageOf(error) };
      if (error instanceof ApiError && [401, 403, 404].includes(error.status)) {
        emit('denied');
      }
    }
  });
  onCleanup(() => {
    controller.abort();
    if (url) {
      URL.revokeObjectURL(url);
    }
  });
}, { immediate: true });
const current = computed(() => file.value.path === props.path ? file.value : null);
</script>

<template>
  <KbDrawer title="指定版本预览" :open="!!path" @close="emit('close')">
    <KbAlert v-if="current?.error" type="warning" :title="current.error" />
    <iframe v-else-if="current?.url" class="preview-frame" title="PDF 版本预览" :src="current.url" referrerpolicy="no-referrer" />
    <SafeText v-else-if="current?.text != null" :text="current.text" />
    <KbSpin v-else />
  </KbDrawer>
</template>
