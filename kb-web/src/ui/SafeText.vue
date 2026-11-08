<script setup lang="ts">
defineProps<{ text: string }>();
</script>

<template>
  <pre class="content-text">{{ text }}</pre>
</template>
