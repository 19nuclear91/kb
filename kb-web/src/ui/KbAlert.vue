<script setup lang="ts">
import { ElAlert } from 'element-plus';

// 对应 antd Alert：默认不可关闭（ElAlert 默认可关闭），提供 action 插槽放置操作按钮。
withDefaults(defineProps<{ type?: 'success' | 'info' | 'warning' | 'error'; title?: string; description?: string; showIcon?: boolean; closable?: boolean }>(), {
  type: 'info', title: undefined, description: undefined, showIcon: false, closable: false,
});
const emit = defineEmits<{ close: [] }>();
</script>

<template>
  <div class="kb-alert">
    <ElAlert :type="type" :title="title" :description="description" :show-icon="showIcon" :closable="closable" @close="emit('close')">
      <template v-if="$slots.default" #default><slot /></template>
    </ElAlert>
    <div v-if="$slots.action" class="kb-alert__action"><slot name="action" /></div>
  </div>
</template>
