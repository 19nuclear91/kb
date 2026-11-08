<script setup lang="ts">
defineProps<{ value: string }>();
</script>

<template><time :datetime="value" :title="value">{{ new Date(value).toLocaleString('zh-CN', { timeZoneName: 'shortOffset' }) }}</time></template>
