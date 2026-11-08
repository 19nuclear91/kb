<script setup lang="ts">
import SubmittedVersion from './SubmittedVersion.vue';
import ReadableProgress from './ReadableProgress.vue';

defineProps<{ documentId: string; versionId: string; revision?: number; submitted?: boolean }>();
const emit = defineEmits<{ denied: [] }>();
</script>

<template>
  <SubmittedVersion v-if="submitted" :document-id="documentId" :version-id="versionId" />
  <ReadableProgress v-else :document-id="documentId" :version-id="versionId" :revision="revision" @denied="emit('denied')" />
</template>
