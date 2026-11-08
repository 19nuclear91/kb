<script setup lang="ts">
import LifecycleScope from './LifecycleScope.vue';
import UserLifecycleContent from './UserLifecycleContent.vue';

withDefaults(defineProps<{ userId: string; refreshRevision?: number }>(), { refreshRevision: 0 });
const emit = defineEmits<{ changed: [] }>();
</script>

<template><LifecycleScope :target="`user:${userId}:refresh:${refreshRevision}`"><UserLifecycleContent :user-id="userId" @changed="emit('changed')" /></LifecycleScope></template>
