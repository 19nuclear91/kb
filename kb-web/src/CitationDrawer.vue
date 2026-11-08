<script setup lang="ts">
import type { Citation } from './types';
import { citationPath } from './logic';
import KbDrawer from './ui/KbDrawer.vue';
import CitationContent from './CitationContent.vue';

defineProps<{ citation: Citation | null }>();
const emit = defineEmits<{ close: [] }>();
</script>

<template>
  <KbDrawer title="版本引用" :open="!!citation" @close="emit('close')"><CitationContent v-if="citation" :key="citationPath(citation) ?? 'unknown'" :citation="citation" /></KbDrawer>
</template>
