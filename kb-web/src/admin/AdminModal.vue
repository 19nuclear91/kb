<script setup lang="ts">
import type { AdminWrite } from './hooks';
import KbDialog from '../ui/KbDialog.vue';

const props = defineProps<{ title: string; write: AdminWrite }>();
const emit = defineEmits<{ close: [] }>();
function close() {
  if (!props.write.busy) {
    emit('close');
  }
}
</script>

<template>
  <KbDialog open :title="title" :show-close="!write.busy" :close-on-click-modal="!write.busy" @close="close"><slot /></KbDialog>
</template>
