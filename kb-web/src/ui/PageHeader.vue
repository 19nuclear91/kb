<script setup lang="ts">
defineProps<{ title: string; description?: string }>();
</script>

<template>
  <header class="page-header">
    <div>
      <h1>{{ title }}</h1>
      <div class="muted">{{ description }}</div>
    </div>
    <slot name="extra" />
  </header>
</template>
