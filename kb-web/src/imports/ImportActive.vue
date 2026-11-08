<script setup lang="ts">
import ImportTask from './ImportTask.vue';
defineProps<{ importId: string; files: Map<string, File>; autoStart: boolean; concurrency: number }>();
</script>
<template>
  <ImportTask :import-id="importId" :initial-files="files" :auto-start="autoStart" :concurrency="concurrency" />
</template>
