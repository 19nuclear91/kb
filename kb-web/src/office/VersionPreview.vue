<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue';
import { useSession } from '../SessionProvider';
import PreviewOpening from './PreviewOpening.vue';

const props = defineProps<{ path: string | null; fileName?: string }>();
const emit = defineEmits<{ close: []; denied: [] }>();
const auth = useSession();
const hidden = ref(false);
watch(() => auth.state.epoch, () => {
  hidden.value = true;
  emit('close');
}, { flush: 'sync' });
const hide = () => {
  if (document.visibilityState === 'hidden') {
    hidden.value = true;
    emit('close');
  }
};
document.addEventListener('visibilitychange', hide);
onScopeDispose(() => document.removeEventListener('visibilitychange', hide));
watch(() => props.path, path => {
  if (!path) {
    hidden.value = false;
  }
});
watch([() => auth.state.phase, () => props.path], ([phase, path]) => {
  if (phase !== 'ready' && path) {
    emit('close');
  }
}, { immediate: true });
const visible = () => !hidden.value && auth.state.phase === 'ready' && document.visibilityState !== 'hidden';
</script>

<template>
  <PreviewOpening v-if="path && visible()" :key="`${auth.state.epoch}:${path}`" :path="path" :file-name="fileName" @close="emit('close')" @denied="emit('denied')" />
</template>
