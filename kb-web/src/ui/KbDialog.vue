<script setup lang="ts">
import { ElDialog } from 'element-plus';

// 完全受控：关闭请求只通知父组件，由 open 决定去留；关闭即卸载，不依赖过渡结束事件，
// 保证关闭后正文、预览与本地输入立即释放，提交中也可由父组件拒绝关闭。
defineOptions({ inheritAttrs: false });
defineProps<{ open: boolean; title?: string; width?: string | number }>();
const emit = defineEmits<{ close: [] }>();
const requestClose = () => emit('close');
</script>

<template>
  <ElDialog v-if="open" :model-value="true" :title="title" :width="width ?? 520" append-to-body v-bind="$attrs" :before-close="requestClose">
    <slot />
    <template v-if="$slots.footer" #footer><slot name="footer" /></template>
  </ElDialog>
</template>
