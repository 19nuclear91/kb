<script setup lang="ts">
import { ElDrawer } from 'element-plus';

// 与 KbDialog 相同：完全受控，关闭即卸载抽屉内容。
defineOptions({ inheritAttrs: false });
defineProps<{ open: boolean; title?: string; size?: string | number }>();
const emit = defineEmits<{ close: [] }>();
const requestClose = () => emit('close');
</script>

<template>
  <ElDrawer v-if="open" :model-value="true" :title="title" :size="size ?? 736" append-to-body v-bind="$attrs" :before-close="requestClose">
    <slot />
    <template v-if="$slots.footer" #footer><slot name="footer" /></template>
  </ElDrawer>
</template>
