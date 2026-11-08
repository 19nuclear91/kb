<script setup lang="ts">
defineProps<{ value: string }>();
</script>

<template><time :datetime="value" :title="value">{{ value }}</time></template>
